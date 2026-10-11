package zelisline.ub.crm.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import zelisline.ub.integrations.whatsapp.application.WhatsAppChannelSettingsService;
import zelisline.ub.notifications.application.WebOrderLifecycleEvent;

/**
 * Turns web-order lifecycle events into {@code SHOP_EVENT} automation firings (scope P2 #13/#14).
 *
 * <p>Consumes {@link WebOrderLifecycleEvent} published by the notification path, ensures the
 * order's customer has a CRM contact + conversation, then evaluates the shop's
 * {@code SHOP_EVENT} rules. Best-effort: any failure is logged and swallowed so a web order (or
 * the notification that produced it) is never blocked.
 *
 * <p>Gated on the WhatsApp channel being live — the CRM is cloud-only and has nothing to send on
 * a deployment without an active number.
 */
@Component
@RequiredArgsConstructor
public class CrmOrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(CrmOrderEventListener.class);

    private final WhatsAppChannelSettingsService channelSettings;
    private final CrmOrderLinkingService linkingService;
    private final CrmAutomationService automationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWebOrderLifecycle(WebOrderLifecycleEvent event) {
        if (event == null || event.businessId() == null || event.eventName() == null) {
            return;
        }
        if (!channelSettings.inboundEnabled()) {
            return;
        }
        try {
            CrmOrderLinkingService.Handle handle = linkingService.ensureForOrder(
                    event.businessId(), event.customerPhone(), event.customerName());
            if (handle == null) {
                return;
            }
            automationService.runShopEventTriggers(
                    event.businessId(), handle.conversationId(), handle.contactId(), event.eventName());
        } catch (Exception ex) {
            log.warn("CRM order-event automation failed for business {} on {}: {}",
                    event.businessId(), event.eventName(), ex.getMessage());
        }
    }
}
