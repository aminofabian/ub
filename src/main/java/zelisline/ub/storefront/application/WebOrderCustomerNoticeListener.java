package zelisline.ub.storefront.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;

import zelisline.ub.notifications.application.WebOrderLifecycleEvent;

/**
 * Tells the customer when an online order is paid (scope P2 #16).
 *
 * <p>The merchant-dashboard actions already message the shopper, but a payment taken by the
 * gateway webhook (Daraja/Paystack) had no customer notice at all — a guest could pay and never
 * hear anything. Every paid path (gateway webhook and merchant mark-paid) publishes
 * {@link WebOrderLifecycleEvent#ORDER_PAID} through the notification outbox, so this listener is
 * the single place the "payment received" message goes out.
 *
 * <p>{@code @Async} so the send never delays the payment webhook's response — the same shape as
 * {@code TenantOpsAlertListener}.
 */
@Component
@RequiredArgsConstructor
public class WebOrderCustomerNoticeListener {

    private static final Logger log = LoggerFactory.getLogger(WebOrderCustomerNoticeListener.class);

    private final WebOrderCustomerMessageService customerMessageService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWebOrderLifecycle(WebOrderLifecycleEvent event) {
        if (event == null
                || event.businessId() == null
                || event.orderId() == null
                || !WebOrderLifecycleEvent.ORDER_PAID.equals(event.eventName())) {
            return;
        }
        try {
            customerMessageService.sendStatusNotice(
                    event.businessId(), event.orderId(), "payment_received", false);
        } catch (RuntimeException ex) {
            log.warn("web_order paid notice failed order={}: {}", event.orderId(), ex.getMessage());
        }
    }
}
