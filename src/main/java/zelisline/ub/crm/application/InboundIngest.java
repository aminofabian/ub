package zelisline.ub.crm.application;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.domain.CrmContact;
import zelisline.ub.crm.domain.CrmConversation;
import zelisline.ub.crm.domain.CrmMessage;
import zelisline.ub.crm.domain.CrmWebhookEvent;
import zelisline.ub.crm.events.CrmConversationUpdatedEvent;
import zelisline.ub.crm.events.CrmMessageCreatedEvent;
import zelisline.ub.crm.repository.CrmContactRepository;
import zelisline.ub.crm.repository.CrmConversationRepository;
import zelisline.ub.crm.repository.CrmMessageRepository;
import zelisline.ub.crm.repository.CrmWebhookEventRepository;
import zelisline.ub.integrations.whatsapp.application.ChannelResolver;
import zelisline.ub.integrations.whatsapp.application.WhatsAppChannelSettingsService;

/**
 * Persists an inbound WhatsApp message into the CRM (contact + conversation + message),
 * after resolving the owning shop from the Meta number.
 *
 * <p>M1 scope; gated by {@code app.integrations.whatsapp.enabled}. The caller (the Meta
 * webhook) swallows failures — it must always return 200 to Meta. An unrouted number is
 * recorded as a raw {@code crm_webhook_event} for super-admin triage and never creates a
 * conversation.
 *
 * <p>Input is an {@link InboundEnvelope} (not a transport type) so the CRM does not depend
 * on the {@code messaging} package. See {@code docs/scopes/whatsapp-crm/SCOPE.md} §6.4.
 */
@Service
@RequiredArgsConstructor
public class InboundIngest {

    private static final Logger log = LoggerFactory.getLogger(InboundIngest.class);

    private static final long WINDOW_SECONDS = 24L * 60 * 60;

    private final WhatsAppChannelSettingsService channelSettings;
    private final ChannelResolver channelResolver;
    private final CrmWebhookEventRepository webhookEventRepository;
    private final CrmContactRepository contactRepository;
    private final CrmConversationRepository conversationRepository;
    private final CrmMessageRepository messageRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CrmAutoReplyService autoReplyService;
    private final CrmAutomationService automationService;

    /**
     * @param envelope the parsed inbound message
     * @param rawJson  the raw webhook body (stored for idempotency + replay)
     */
    @Transactional
    public void ingest(InboundEnvelope envelope, String rawJson) {
        if (!channelSettings.inboundEnabled() || envelope == null) {
            return;
        }
        if (envelope.wamid() != null && webhookEventRepository.existsByWamid(envelope.wamid())) {
            return; // Meta re-delivery
        }

        String businessId = channelResolver.businessIdForPhoneNumber(envelope.phoneNumberId()).orElse(null);
        recordEnvelope(envelope, rawJson, businessId);

        if (businessId == null) {
            log.info("CRM ingest: unrouted phone_number_id={} — kept for triage", envelope.phoneNumberId());
            return;
        }

        String phone = digits(envelope.from());
        if (phone == null) {
            return;
        }

        CrmContact existing = contactRepository.findByBusinessIdAndPhoneE164(businessId, phone).orElse(null);
        boolean firstInbound = existing == null;
        CrmContact contact;
        if (existing != null) {
            contact = existing;
        } else {
            CrmContact created = new CrmContact();
            created.setBusinessId(businessId);
            created.setPhoneE164(phone);
            created.setName(envelope.senderName());
            contact = contactRepository.save(created);
        }

        CrmConversation conversation = conversationRepository
                .findFirstByBusinessIdAndContactIdAndStatusOrderByUpdatedAtDesc(
                        businessId, contact.getId(), CrmConversation.STATUS_OPEN)
                .orElseGet(() -> {
                    CrmConversation created = new CrmConversation();
                    created.setBusinessId(businessId);
                    created.setContactId(contact.getId());
                    created.setStatus(CrmConversation.STATUS_OPEN);
                    created.setPhoneNumberId(envelope.phoneNumberId());
                    return conversationRepository.save(created);
                });
        if (conversation.getPhoneNumberId() == null || conversation.getPhoneNumberId().isBlank()) {
            conversation.setPhoneNumberId(envelope.phoneNumberId());
        }

        Instant at = envelope.receivedAt() != null ? envelope.receivedAt() : Instant.now();

        if (envelope.wamid() == null || !messageRepository.existsByWaMessageId(envelope.wamid())) {
            CrmMessage row = new CrmMessage();
            row.setConversationId(conversation.getId());
            row.setBusinessId(businessId);
            row.setDirection(CrmMessage.DIRECTION_INBOUND);
            row.setWaMessageId(envelope.wamid());
            row.setType(envelope.type() == null ? "unknown" : envelope.type());
            row.setBody(envelope.content());
            row.setStatus(CrmMessage.STATUS_RECEIVED);
            row.setCreatedAt(at);
            messageRepository.save(row);
            eventPublisher.publishEvent(new CrmMessageCreatedEvent(
                    businessId, conversation.getId(), row.getId(), CrmMessage.DIRECTION_INBOUND));
        }

        conversation.setLastMessageAt(at);
        conversation.setUnreadCount(conversation.getUnreadCount() + 1);
        conversation.setWindowExpiresAt(at.plusSeconds(WINDOW_SECONDS));
        conversation.setUpdatedAt(Instant.now());
        conversationRepository.save(conversation);
        eventPublisher.publishEvent(new CrmConversationUpdatedEvent(
                businessId,
                conversation.getId(),
                conversation.getStatus(),
                conversation.getAssignedUserId(),
                conversation.getUnreadCount()));

        try {
            autoReplyService.maybeAutoReply(businessId, conversation, phone, envelope.content());
        } catch (Exception ex) {
            log.warn("CRM auto-reply failed (continuing): {}", ex.getMessage());
        }

        try {
            automationService.runInboundTriggers(
                    businessId, conversation.getId(), contact.getId(), envelope.content(), firstInbound);
        } catch (Exception ex) {
            log.warn("CRM automation failed (continuing): {}", ex.getMessage());
        }
    }

    private void recordEnvelope(InboundEnvelope envelope, String rawJson, String businessId) {
        CrmWebhookEvent event = new CrmWebhookEvent();
        event.setWamid(envelope.wamid());
        event.setPhoneNumberId(envelope.phoneNumberId());
        event.setBusinessId(businessId);
        event.setRawJson(rawJson == null ? "" : rawJson);
        event.setProcessedAt(Instant.now());
        webhookEventRepository.save(event);
    }

    private static String digits(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.replaceAll("\\D", "");
        return trimmed.isEmpty() ? null : trimmed;
    }
}
