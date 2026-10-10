package zelisline.ub.crm.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import zelisline.ub.crm.domain.CrmBroadcastRecipient;
import zelisline.ub.crm.domain.CrmConversation;
import zelisline.ub.crm.domain.CrmMessage;
import zelisline.ub.crm.domain.CrmOutbound;
import zelisline.ub.crm.repository.CrmBroadcastRecipientRepository;
import zelisline.ub.crm.repository.CrmConversationRepository;
import zelisline.ub.crm.repository.CrmMessageRepository;
import zelisline.ub.crm.repository.CrmOutboundRepository;
import zelisline.ub.integrations.whatsapp.application.ChannelResolver;
import zelisline.ub.integrations.whatsapp.application.WhatsAppChannelSender;

/**
 * One outbound send attempt in its own transaction (isolates failures across the batch),
 * mirroring {@code integrations/webhook WebhookDeliveryTxnService}. On success both the
 * outbox row and the {@code crm_message} flip to sent, and the returned wamid is stored so
 * delivery-status callbacks can be applied later; failures retry with capped backoff until
 * {@code max-attempts}, then both go failed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrmOutboundTxnService {

    private final CrmOutboundRepository outboundRepository;
    private final CrmMessageRepository messageRepository;
    private final CrmBroadcastRecipientRepository recipientRepository;
    private final CrmConversationRepository conversationRepository;
    private final ChannelResolver channelResolver;
    private final WhatsAppChannelSender channelSender;
    private final ObjectMapper objectMapper;

    @Value("${app.integrations.whatsapp.outbox.max-attempts:8}")
    private int maxAttempts;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attempt(String outboundId) {
        CrmOutbound row = outboundRepository.findById(outboundId).orElse(null);
        if (row == null || !CrmOutbound.STATUS_PENDING.equals(row.getStatus())) {
            return;
        }

        String messageId = null;
        String recipientId = null;
        String fromNumber = resolveFromNumber(row);
        WhatsAppChannelSender.SendResult result;
        try {
            JsonNode payload = objectMapper.readTree(row.getPayloadJson());
            messageId = textOrNull(payload, "messageId");
            recipientId = textOrNull(payload, "recipientId");
            String type = payload.path("type").asText("text");
            if ("template".equals(type)) {
                String templateName = textOrNull(payload, "templateName");
                if (templateName == null || templateName.isBlank()) {
                    markFailed(row, messageId, recipientId, "missing_template");
                    return;
                }
                result = channelSender.sendTemplate(
                        row.getToPhoneE164(), templateName, textOrNull(payload, "templateLanguage"),
                        readParams(payload), fromNumber);
            } else {
                String body = textOrNull(payload, "body");
                if (body == null || body.isBlank()) {
                    markFailed(row, messageId, recipientId, "empty_body");
                    return;
                }
                result = channelSender.sendText(row.getToPhoneE164(), body, fromNumber);
            }
        } catch (Exception ex) {
            markFailed(row, null, null, "unparseable_payload");
            return;
        }

        if (result.sent()) {
            row.setStatus(CrmOutbound.STATUS_SENT);
            row.setLastError(null);
            row.setUpdatedAt(Instant.now());
            outboundRepository.save(row);
            markMessageSent(messageId, result.whatsappMessageId());
            markRecipientSent(recipientId, result.whatsappMessageId());
            return;
        }

        onFailure(row, messageId, recipientId, result.detail());
    }

    /** The shop's number to reply from: the conversation's bound number, else its single route. */
    private String resolveFromNumber(CrmOutbound row) {
        if (row.getConversationId() != null) {
            String bound = conversationRepository.findById(row.getConversationId())
                    .map(CrmConversation::getPhoneNumberId)
                    .orElse(null);
            if (bound != null && !bound.isBlank()) {
                return bound.trim();
            }
        }
        return channelResolver.activePhoneNumberIdForBusiness(row.getBusinessId()).orElse(null);
    }

    private void onFailure(CrmOutbound row, String messageId, String recipientId, String error) {
        int nextAttempt = row.getAttemptCount() + 1;
        row.setAttemptCount(nextAttempt);
        row.setLastError(truncate(error));
        row.setUpdatedAt(Instant.now());

        if (nextAttempt >= maxAttempts) {
            row.setStatus(CrmOutbound.STATUS_FAILED);
            row.setNextAttemptAt(null);
            outboundRepository.save(row);
            updateMessageStatus(messageId, CrmMessage.STATUS_FAILED, truncate(error));
            markRecipientFailed(recipientId, truncate(error));
            log.warn("CRM outbound permanently failed id={} attempts={} error={}", row.getId(), nextAttempt, error);
            return;
        }

        row.setStatus(CrmOutbound.STATUS_PENDING);
        row.setNextAttemptAt(Instant.now().plusSeconds(backoffSeconds(nextAttempt)));
        outboundRepository.save(row);
        log.debug("CRM outbound retry scheduled id={} attempt={} error={}", row.getId(), nextAttempt, error);
    }

    private void markFailed(CrmOutbound row, String messageId, String recipientId, String error) {
        row.setStatus(CrmOutbound.STATUS_FAILED);
        row.setLastError(truncate(error));
        row.setNextAttemptAt(null);
        row.setUpdatedAt(Instant.now());
        outboundRepository.save(row);
        updateMessageStatus(messageId, CrmMessage.STATUS_FAILED, truncate(error));
        markRecipientFailed(recipientId, truncate(error));
    }

    /** Success: flip the message to sent and store Meta's wamid for later status correlation. */
    private void markMessageSent(String messageId, String wamid) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        messageRepository.findById(messageId).ifPresent(message -> {
            message.setStatus(CrmMessage.STATUS_SENT);
            if (wamid != null && !wamid.isBlank()) {
                message.setWaMessageId(wamid);
            }
            messageRepository.save(message);
        });
    }

    private void updateMessageStatus(String messageId, String status, String failureReason) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        messageRepository.findById(messageId).ifPresent(message -> {
            message.setStatus(status);
            if (failureReason != null) {
                message.setFailureReason(failureReason);
            }
            messageRepository.save(message);
        });
    }

    private void markRecipientSent(String recipientId, String wamid) {
        if (isBlank(recipientId)) {
            return;
        }
        recipientRepository.findById(recipientId).ifPresent(recipient -> {
            if (!CrmBroadcastRecipient.STATUS_PENDING.equals(recipient.getStatus())) {
                return;
            }
            recipient.setStatus(CrmBroadcastRecipient.STATUS_SENT);
            if (wamid != null && !wamid.isBlank()) {
                recipient.setWaMessageId(wamid);
            }
            Instant now = Instant.now();
            recipient.setSentAt(now);
            recipient.setUpdatedAt(now);
            recipient.setErrorMessage(null);
            recipientRepository.save(recipient);
        });
    }

    private void markRecipientFailed(String recipientId, String error) {
        if (isBlank(recipientId)) {
            return;
        }
        recipientRepository.findById(recipientId).ifPresent(recipient -> {
            if (!CrmBroadcastRecipient.STATUS_PENDING.equals(recipient.getStatus())) {
                return;
            }
            recipient.setStatus(CrmBroadcastRecipient.STATUS_FAILED);
            recipient.setErrorMessage(error);
            recipient.setUpdatedAt(Instant.now());
            recipientRepository.save(recipient);
        });
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText(null);
    }

    private static List<String> readParams(JsonNode payload) {
        JsonNode array = payload.path("templateParams");
        if (!array.isArray()) {
            return List.of();
        }
        List<String> params = new ArrayList<>();
        array.forEach(node -> params.add(node.isNull() ? "" : node.asText("")));
        return params;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Capped exponential backoff with jitter — matches the webhook worker shape. */
    private static long backoffSeconds(int attemptAfterIncrement) {
        int exp = Math.min(10, Math.max(1, attemptAfterIncrement));
        long base = Math.min(3600L, (1L << exp) * 15L);
        int jitter = ThreadLocalRandom.current().nextInt(0, 30);
        return base + jitter;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
