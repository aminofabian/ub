package zelisline.ub.opsalerts.application;

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

@Component
@RequiredArgsConstructor
public class TenantOpsAlertListener {

    private static final Logger log = LoggerFactory.getLogger(TenantOpsAlertListener.class);

    private final TenantOpsAlertDispatcher dispatcher;
    private final CustomerRepository customerRepository;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onWebOrderPlaced(WebOrderPlacedOpsAlertEvent event) {
        try {
            if (event == null || event.businessId() == null) {
                return;
            }
            log.info("Ops alert event web_order business={} order={}", event.businessId(), event.orderId());
            String shop = dispatcher.shopName(event.businessId());
            String currency = event.currency() != null && !event.currency().isBlank()
                    ? event.currency()
                    : dispatcher.currency(event.businessId());
            String message = shop + " — new web order\n"
                    + "Customer: " + safe(event.customerName()) + "\n"
                    + "Phone: " + safe(event.customerPhone()) + "\n"
                    + "Total: " + TenantOpsAlertDispatcher.formatMoney(event.grandTotal(), currency) + "\n"
                    + "Order: " + shortId(event.orderId());
            dispatcher.dispatch(event.businessId(), OpsAlertType.WEB_ORDER, message);
        } catch (Exception ex) {
            log.warn("Ops alert web order failed order={}", event != null ? event.orderId() : null, ex);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onShiftOpened(RealtimeBridge.ShiftOpenedEvent event) {
        try {
            log.info("Ops alert event shift_opened business={} shift={}", event.businessId(), event.shiftId());
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
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onShiftClosed(RealtimeBridge.ShiftClosedEvent event) {
        try {
            log.info("Ops alert event shift_closed business={} shift={}", event.businessId(), event.shiftId());
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
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSupplyPosted(RealtimeBridge.SupplyPostedEvent event) {
        try {
            log.info(
                    "Ops alert event supply_posted business={} invoice={}",
                    event.businessId(),
                    event.supplierInvoiceId());
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
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreditTabPaymentConfirmed(CreditTabPaymentConfirmationEvent event) {
        try {
            log.info("Ops alert event credit_stk business={} intent={}", event.businessId(), event.intentId());
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
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreditPaymentRecorded(CreditPaymentOpsAlertEvent event) {
        try {
            log.info("Ops alert event credit_payment business={} via={}", event.businessId(), event.channel());
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
}
