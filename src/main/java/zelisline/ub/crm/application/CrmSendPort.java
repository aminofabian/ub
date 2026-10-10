package zelisline.ub.crm.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.domain.CrmMessage;
import zelisline.ub.crm.domain.CrmOutbound;
import zelisline.ub.crm.repository.CrmMessageRepository;
import zelisline.ub.crm.repository.CrmOutboundRepository;

/**
 * Enqueues an outbound CRM message: it writes the {@code crm_message} row (status queued) and
 * a {@code crm_outbound} row in the caller's transaction, so the caller (inbox, automation)
 * never blocks on Meta. {@link CrmOutboundWorker} drains the outbox.
 *
 * <p>See {@code docs/scopes/whatsapp-crm/SCOPE.md} §6.5. The dry-run/send API that calls this
 * arrives in M2; M1 lands the port + drain only.
 */
@Service
@RequiredArgsConstructor
public class CrmSendPort {

    private final CrmOutboundRepository outboundRepository;
    private final CrmMessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    /**
     * @param idempotencyKeyOrNull dedupes per business; omit for fire-and-repeat.
     * @return the created {@code crm_message} id, or {@code null} when de-duplicated
     */
    @Transactional
    public String enqueueText(
            String businessId,
            String conversationId,
            String toPhoneE164,
            String body,
            String idempotencyKeyOrNull
    ) {
        if (idempotencyKeyOrNull != null
                && outboundRepository.existsByBusinessIdAndIdempotencyKey(businessId, idempotencyKeyOrNull)) {
            return null;
        }

        Instant now = Instant.now();

        CrmMessage message = new CrmMessage();
        message.setBusinessId(businessId);
        message.setConversationId(conversationId);
        message.setDirection(CrmMessage.DIRECTION_OUTBOUND);
        message.setType("text");
        message.setBody(body);
        message.setStatus(CrmMessage.STATUS_QUEUED);
        message.setCreatedAt(now);
        CrmMessage saved = messageRepository.save(message);

        CrmOutbound outbound = new CrmOutbound();
        outbound.setBusinessId(businessId);
        outbound.setConversationId(conversationId);
        outbound.setToPhoneE164(toPhoneE164);
        outbound.setStatus(CrmOutbound.STATUS_PENDING);
        outbound.setIdempotencyKey(idempotencyKeyOrNull);
        outbound.setPayloadJson(payloadJson(saved.getId(), body));
        outboundRepository.save(outbound);

        return saved.getId();
    }

    private String payloadJson(String messageId, String body) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "text");
        payload.put("messageId", messageId);
        payload.put("body", body);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("crm outbound payload serialization failed", e);
        }
    }
}
