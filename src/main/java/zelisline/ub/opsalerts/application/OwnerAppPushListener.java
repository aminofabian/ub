package zelisline.ub.opsalerts.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import zelisline.ub.messaging.application.CreditTabPaymentConfirmationEvent;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.SalesDayBands;
import zelisline.ub.notifications.application.OwnerDevicePushService;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.sales.repository.SaleRepository;

/**
 * The same shop moments the ops alerts already hear, dressed for the owner phone:
 * a shift, a delivery of supplies, a web order's siblings, a void's neighbours,
 * and each KES 5,000 the day crosses.
 */
@Component
@RequiredArgsConstructor
public class OwnerAppPushListener {

    private static final Logger log = LoggerFactory.getLogger(OwnerAppPushListener.class);
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final OwnerDevicePushService pushes;
    private final TenantOpsAlertDispatcher names;
    private final SaleRepository sales;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onShiftOpened(RealtimeBridge.ShiftOpenedEvent event) {
        try {
            String branch = names.branchName(event.businessId(), event.branchId());
            String money = TenantOpsAlertDispatcher.formatMoney(event.openingCash(), names.currency(event.businessId()));
            pushes.pushOnce(
                    event.businessId(),
                    NotificationTypes.SHIFT_OPENED,
                    "shift.opened:" + event.shiftId(),
                    "Shift opened",
                    branch + " is open. Float " + money + ".");
        } catch (RuntimeException ex) {
            log.warn("Owner push shift opened failed shift={}", event.shiftId(), ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onShiftClosed(RealtimeBridge.ShiftClosedEvent event) {
        try {
            String branch = names.branchName(event.businessId(), event.branchId());
            String currency = names.currency(event.businessId());
            String counted = TenantOpsAlertDispatcher.formatMoney(event.countedCash(), currency);
            String expected = TenantOpsAlertDispatcher.formatMoney(event.expectedCash(), currency);
            pushes.pushOnce(
                    event.businessId(),
                    NotificationTypes.SHIFT_CLOSED,
                    "shift.closed:" + event.shiftId(),
                    "Shift closed",
                    branch + " closed. Counted " + counted + ". Expected " + expected + ".");
        } catch (RuntimeException ex) {
            log.warn("Owner push shift closed failed shift={}", event.shiftId(), ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSupplyPosted(RealtimeBridge.SupplyPostedEvent event) {
        try {
            String branch = names.branchName(event.businessId(), event.branchId());
            String invoice = event.invoiceNumber() != null && !event.invoiceNumber().isBlank()
                    ? event.invoiceNumber().trim()
                    : "A supply bill";
            String money = TenantOpsAlertDispatcher.formatMoney(event.amount(), names.currency(event.businessId()));
            pushes.pushOnce(
                    event.businessId(),
                    NotificationTypes.SUPPLY_POSTED,
                    "supply.posted:" + event.supplierInvoiceId(),
                    "Supplies in",
                    invoice + " posted at " + branch + " · " + money + ".");
        } catch (RuntimeException ex) {
            log.warn("Owner push supply failed invoice={}", event.supplierInvoiceId(), ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSaleCompleted(RealtimeBridge.SaleCompletedEvent event) {
        try {
            ZonedDateTime start = LocalDate.now(NAIROBI).atStartOfDay(NAIROBI);
            Instant from = start.toInstant();
            Instant to = start.plusDays(1).toInstant();
            BigDecimal today = sales.sumCompletedBetween(event.businessId(), from, to);
            if (today == null) {
                return;
            }
            String day = start.toLocalDate().toString();
            String currency = names.currency(event.businessId());
            for (BigDecimal band : SalesDayBands.crossed(today, event.amount())) {
                String money = TenantOpsAlertDispatcher.formatMoney(band, currency);
                pushes.pushOnce(
                        event.businessId(),
                        NotificationTypes.SALES_MILESTONE,
                        "sales.milestone:" + day + ":" + band.toPlainString(),
                        money + " today",
                        "Sales today have passed " + money + ".");
            }
        } catch (RuntimeException ex) {
            log.warn("Owner push sales milestone failed sale={}", event.saleId(), ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreditPaid(CreditTabPaymentConfirmationEvent event) {
        try {
            if (event.businessId() == null || event.referenceId() == null || event.referenceId().isBlank()) {
                return;
            }
            String currency = names.currency(event.businessId());
            String paid = TenantOpsAlertDispatcher.formatMoney(event.amountPaid(), currency);
            String left = TenantOpsAlertDispatcher.formatMoney(event.balanceRemaining(), currency);
            String via = event.paymentMethod() == null || event.paymentMethod().isBlank()
                    ? "M-Pesa"
                    : event.paymentMethod().trim();
            pushes.pushOnce(
                    event.businessId(),
                    NotificationTypes.CREDIT_PAYMENT,
                    "credit.payment:" + event.referenceId(),
                    "Credit payment",
                    paid + " received by " + via + ". Tab left " + left + ".");
        } catch (RuntimeException ex) {
            log.warn("Owner push credit payment failed reference={}", event.referenceId(), ex);
        }
    }
}
