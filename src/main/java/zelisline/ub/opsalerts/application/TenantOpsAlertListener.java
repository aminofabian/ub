package zelisline.ub.opsalerts.application;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.messaging.application.CreditTabPaymentConfirmationEvent;
import zelisline.ub.opsalerts.domain.OpsAlertType;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.storefront.domain.WebOrder;

@Component
@RequiredArgsConstructor
public class TenantOpsAlertListener {

    private static final Logger log = LoggerFactory.getLogger(TenantOpsAlertListener.class);

    private final TenantOpsAlertDispatcher dispatcher;
    private final CustomerRepository customerRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWebOrderPlaced(WebOrderPlacedOpsAlertEvent event) {
        try {
            WebOrder order = event.order();
            if (order == null) {
                return;
            }
            String shop = dispatcher.shopName(order.getBusinessId());
            String currency = order.getCurrency() != null ? order.getCurrency() : dispatcher.currency(order.getBusinessId());
            String message = shop + " — new web order\n"
                    + "Customer: " + safe(order.getCustomerName()) + "\n"
                    + "Phone: " + safe(order.getCustomerPhone()) + "\n"
                    + "Total: " + TenantOpsAlertDispatcher.formatMoney(order.getGrandTotal(), currency) + "\n"
                    + "Order: " + shortId(order.getId());
            dispatcher.dispatch(order.getBusinessId(), OpsAlertType.WEB_ORDER, message);
        } catch (Exception ex) {
            log.warn("Ops alert web order failed", ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftOpened(RealtimeBridge.ShiftOpenedEvent event) {
        try {
            String shop = dispatcher.shopName(event.businessId());
            String branch = dispatcher.branchName(event.businessId(), event.branchId());
            String currency = dispatcher.currency(event.businessId());
            String message = shop + " — shift opened\n"
                    + "Branch: " + branch + "\n"
                    + "Opening cash: " + TenantOpsAlertDispatcher.formatMoney(event.openingCash(), currency) + "\n"
                    + "Shift: " + shortId(event.shiftId());
            dispatcher.dispatch(event.businessId(), OpsAlertType.SHIFT_OPENED, message);
        } catch (Exception ex) {
            log.warn("Ops alert shift opened failed shift={}", event.shiftId(), ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShiftClosed(RealtimeBridge.ShiftClosedEvent event) {
        try {
            String shop = dispatcher.shopName(event.businessId());
            String branch = dispatcher.branchName(event.businessId(), event.branchId());
            String currency = dispatcher.currency(event.businessId());
            String message = shop + " — shift closed\n"
                    + "Branch: " + branch + "\n"
                    + "Expected: " + TenantOpsAlertDispatcher.formatMoney(event.expectedCash(), currency) + "\n"
                    + "Counted: " + TenantOpsAlertDispatcher.formatMoney(event.countedCash(), currency) + "\n"
                    + "Variance: " + TenantOpsAlertDispatcher.formatMoney(event.variance(), currency) + "\n"
                    + "Shift: " + shortId(event.shiftId());
            dispatcher.dispatch(event.businessId(), OpsAlertType.SHIFT_CLOSED, message);
        } catch (Exception ex) {
            log.warn("Ops alert shift closed failed shift={}", event.shiftId(), ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSupplyPosted(RealtimeBridge.SupplyPostedEvent event) {
        try {
            String shop = dispatcher.shopName(event.businessId());
            String branch = dispatcher.branchName(event.businessId(), event.branchId());
            String currency = dispatcher.currency(event.businessId());
            String invoice = event.invoiceNumber() != null && !event.invoiceNumber().isBlank()
                    ? event.invoiceNumber()
                    : shortId(event.supplierInvoiceId());
            String message = shop + " — supply bill posted\n"
                    + "Branch: " + branch + "\n"
                    + "Invoice: " + invoice + "\n"
                    + "Amount: " + TenantOpsAlertDispatcher.formatMoney(event.amount(), currency);
            dispatcher.dispatch(event.businessId(), OpsAlertType.SUPPLY_POSTED, message);
        } catch (Exception ex) {
            log.warn("Ops alert supply posted failed invoice={}", event.supplierInvoiceId(), ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreditTabPaymentConfirmed(CreditTabPaymentConfirmationEvent event) {
        try {
            String shop = dispatcher.shopName(event.businessId());
            String currency = dispatcher.currency(event.businessId());
            String customerName = resolveCustomerName(event.customerId(), event.businessId());
            String message = shop + " — credit payment received\n"
                    + "Customer: " + customerName + "\n"
                    + "Paid: " + TenantOpsAlertDispatcher.formatMoney(event.amountPaid(), currency) + "\n"
                    + "Remaining tab: " + TenantOpsAlertDispatcher.formatMoney(event.balanceRemaining(), currency)
                    + "\nVia: M-Pesa STK";
            dispatcher.dispatch(event.businessId(), OpsAlertType.CREDIT_PAYMENT, message);
        } catch (Exception ex) {
            log.warn("Ops alert credit STK payment failed intent={}", event.intentId(), ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreditPaymentRecorded(CreditPaymentOpsAlertEvent event) {
        try {
            String shop = dispatcher.shopName(event.businessId());
            String currency = dispatcher.currency(event.businessId());
            String customerName = event.customerName() != null && !event.customerName().isBlank()
                    ? event.customerName().trim()
                    : resolveCustomerName(event.customerId(), event.businessId());
            String channel = event.channel() != null ? event.channel() : "payment";
            String message = shop + " — credit payment received\n"
                    + "Customer: " + customerName + "\n"
                    + "Paid: " + TenantOpsAlertDispatcher.formatMoney(event.amountPaid(), currency) + "\n"
                    + "Remaining tab: " + TenantOpsAlertDispatcher.formatMoney(event.balanceRemaining(), currency)
                    + "\nVia: " + channel;
            dispatcher.dispatch(event.businessId(), OpsAlertType.CREDIT_PAYMENT, message);
        } catch (Exception ex) {
            log.warn("Ops alert credit payment failed business={}", event.businessId(), ex);
        }
    }

    private String resolveCustomerName(String customerId, String businessId) {
        if (customerId == null || customerId.isBlank()) {
            return "Customer";
        }
        return customerRepository.findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .map(Customer::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse("Customer");
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "—" : value.trim();
    }

    private static String shortId(String id) {
        if (id == null || id.isBlank()) {
            return "—";
        }
        return id.substring(0, Math.min(8, id.length()));
    }

    /** Published after storefront checkout creates a web order. */
    public record WebOrderPlacedOpsAlertEvent(WebOrder order) {
    }

    /** Published after admin/claim credit payment (non-STK paths). */
    public record CreditPaymentOpsAlertEvent(
            String businessId,
            String customerId,
            String customerName,
            BigDecimal amountPaid,
            BigDecimal balanceRemaining,
            String channel
    ) {
    }
}
