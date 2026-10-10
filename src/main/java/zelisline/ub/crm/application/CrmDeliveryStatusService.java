package zelisline.ub.crm.application;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.domain.CrmBroadcastRecipient;
import zelisline.ub.crm.events.CrmMessageStatusEvent;
import zelisline.ub.crm.repository.CrmBroadcastRecipientRepository;
import zelisline.ub.crm.repository.CrmMessageRepository;
import zelisline.ub.integrations.whatsapp.application.WhatsAppChannelSettingsService;

/**
 * Applies Meta delivery-status callbacks (sent / delivered / read / failed) to the CRM by
 * matching the outbound message's {@code wa_message_id}. Gated by
 * {@code app.integrations.whatsapp.enabled}; unknown statuses are ignored.
 *
 * <p>Publishes {@link CrmMessageStatusEvent} so the inbox is refreshed live.
 * See {@code docs/scopes/whatsapp-crm/SCOPE.md} §6.6.
 */
@Service
@RequiredArgsConstructor
public class CrmDeliveryStatusService {

    /** Meta status values we persist (they equal the {@code CrmMessage.STATUS_*} strings). */
    private static final Set<String> KNOWN = Set.of("sent", "delivered", "read", "failed");

    /** Forward-only progression for broadcast recipients. */
    private static final List<String> PROGRESS = List.of("sent", "delivered", "read");

    private final WhatsAppChannelSettingsService channelSettings;
    private final CrmMessageRepository messageRepository;
    private final CrmBroadcastRecipientRepository recipientRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void applyStatus(StatusEnvelope status) {
        if (!channelSettings.inboundEnabled() || status == null) {
            return;
        }
        if (status.wamid() == null || status.wamid().isBlank()) {
            return;
        }
        if (status.status() == null || !KNOWN.contains(status.status())) {
            return;
        }
        messageRepository.findByWaMessageId(status.wamid()).ifPresent(message -> {
            message.setStatus(status.status());
            if ("failed".equals(status.status()) && status.errorDetail() != null) {
                message.setFailureReason(truncate(status.errorDetail()));
            }
            messageRepository.save(message);
            eventPublisher.publishEvent(new CrmMessageStatusEvent(
                    message.getBusinessId(),
                    message.getConversationId(),
                    status.wamid(),
                    status.status()));
        });

        recipientRepository.findByWaMessageId(status.wamid()).ifPresent(recipient ->
                applyRecipientStatus(recipient, status.status(), status.errorDetail()));
    }

    /** Mirrors a Meta status onto a broadcast recipient row (M4), never regressing it. */
    private void applyRecipientStatus(CrmBroadcastRecipient recipient, String status, String errorDetail) {
        Instant now = Instant.now();
        if ("failed".equals(status)) {
            if (!CrmBroadcastRecipient.STATUS_READ.equals(recipient.getStatus())) {
                recipient.setStatus(CrmBroadcastRecipient.STATUS_FAILED);
                recipient.setErrorMessage(errorDetail == null ? null : truncate(errorDetail));
                recipient.setUpdatedAt(now);
                recipientRepository.save(recipient);
            }
            return;
        }
        int incoming = PROGRESS.indexOf(status);
        if (incoming < 0 || incoming <= PROGRESS.indexOf(recipient.getStatus())) {
            return;
        }
        recipient.setStatus(status);
        switch (status) {
            case CrmBroadcastRecipient.STATUS_SENT -> recipient.setSentAt(now);
            case CrmBroadcastRecipient.STATUS_DELIVERED -> recipient.setDeliveredAt(now);
            case CrmBroadcastRecipient.STATUS_READ -> recipient.setReadAt(now);
            default -> { }
        }
        recipient.setUpdatedAt(now);
        recipientRepository.save(recipient);
    }

    private static String truncate(String value) {
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
