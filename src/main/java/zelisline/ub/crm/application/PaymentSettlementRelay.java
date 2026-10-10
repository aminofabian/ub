package zelisline.ub.crm.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.domain.CrmConversation;
import zelisline.ub.crm.domain.CrmNote;
import zelisline.ub.crm.events.CrmConversationUpdatedEvent;
import zelisline.ub.crm.repository.CrmConversationRepository;
import zelisline.ub.crm.repository.CrmNoteRepository;
import zelisline.ub.integrations.whatsapp.application.WhatsAppChannelSettingsService;
import zelisline.ub.platform.realtime.RealtimeBridge;

/**
 * Relays an M-Pesa STK settlement for a WhatsApp payment prompt ({@code WHATSAPP_CHAT}) into the
 * conversation as an internal note, so the agent sees the outcome, and refreshes the inbox.
 *
 * <p>No money state is written here — {@code payments} owns it; this only reflects the result.
 * See {@code docs/scopes/whatsapp-crm/SCOPE.md} §6.7.
 */
@Component
@RequiredArgsConstructor
public class PaymentSettlementRelay {

    static final String WHATSAPP_CHAT_CONTEXT = "WHATSAPP_CHAT";
    private static final String SYSTEM_AUTHOR = "system";

    private final WhatsAppChannelSettingsService channelSettings;
    private final CrmConversationRepository conversationRepository;
    private final CrmNoteRepository noteRepository;
    private final ApplicationEventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSettled(RealtimeBridge.StkPaymentSettledEvent event) {
        if (!channelSettings.inboundEnabled() || event == null) {
            return;
        }
        if (!WHATSAPP_CHAT_CONTEXT.equals(event.contextType())) {
            return;
        }
        if (event.businessId() == null || event.contextId() == null) {
            return;
        }

        CrmConversation conversation = conversationRepository
                .findByIdAndBusinessId(event.contextId(), event.businessId())
                .orElse(null);
        if (conversation == null) {
            return;
        }

        CrmNote note = new CrmNote();
        note.setBusinessId(event.businessId());
        note.setConversationId(conversation.getId());
        note.setAuthorUserId(SYSTEM_AUTHOR);
        note.setBody(settlementNote(event));
        noteRepository.save(note);

        eventPublisher.publishEvent(new CrmConversationUpdatedEvent(
                conversation.getBusinessId(),
                conversation.getId(),
                conversation.getStatus(),
                conversation.getAssignedUserId(),
                conversation.getUnreadCount()));
    }

    private static String settlementNote(RealtimeBridge.StkPaymentSettledEvent event) {
        String amount = event.amount() != null ? event.amount().toPlainString() : "";
        String ref = event.merchantReference() != null ? event.merchantReference() : event.checkoutRequestId();
        String refSuffix = ref != null && !ref.isBlank() ? " (ref " + ref + ")" : "";
        if (event.success()) {
            return "M-Pesa payment received"
                    + (amount.isBlank() ? "" : " — KES " + amount)
                    + refSuffix;
        }
        String reason = event.message() != null && !event.message().isBlank()
                ? event.message()
                : "payment not completed";
        return "M-Pesa " + reason + refSuffix;
    }
}
