package zelisline.ub.messaging.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.grocery.application.RemoteGroceryInvoiceNotifyEvent;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class RemoteGroceryInvoiceNotifyService {

    private static final Logger log = LoggerFactory.getLogger(RemoteGroceryInvoiceNotifyService.class);
    static final int MAX_ITEM_LINES = 5;

    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final BusinessRepository businessRepository;
    private final CustomerMessageDispatcher customerMessageDispatcher;

    @Transactional
    public void dispatch(RemoteGroceryInvoiceNotifyEvent event) {
        TenantMessagingConfig messaging = messagingSettingsService.resolveForDispatch(event.businessId());
        if (!messaging.enabled()) {
            log.info("remote_invoice_notify skipped invoice={} reminders_disabled", event.invoiceId());
            return;
        }
        String phoneDigits = event.customerPhone();
        if (phoneDigits == null || phoneDigits.isBlank()) {
            return;
        }

        Business business = businessRepository.findById(event.businessId()).orElse(null);
        String currency = business != null && business.getCurrency() != null
                ? business.getCurrency().trim()
                : "KES";
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "our shop";
        String accountUrl = CustomerTabPaymentUrl.build(messaging.paymentAccountUrl(), phoneDigits);

        String message = buildMessage(
                shopName,
                event.barcodeCode(),
                event.grandTotal(),
                event.lineCount(),
                event.items(),
                currency,
                accountUrl);

        CustomerMessageDispatcher.DeliveryResult delivery =
                customerMessageDispatcher.deliver(messaging, phoneDigits, message);
        log.info("remote_invoice_notify invoice={} channel={} outcome={} detail={}",
                event.invoiceId(), delivery.channel(), delivery.outcome(), delivery.detail());
    }

    static String buildMessage(
            String shopName,
            String barcodeCode,
            BigDecimal amount,
            int lineCount,
            List<CreditSaleReminderLineItem> items,
            String currency,
            String accountUrl
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi,\n\n");
        sb.append("Your bill at ").append(shopName).append(" is ready:\n");

        List<CreditSaleReminderLineItem> lines = items != null ? items : List.of();
        if (lines.isEmpty()) {
            int count = Math.max(1, lineCount);
            String label = count == 1 ? "item" : "items";
            sb.append("• ").append(count).append(" ").append(label).append("\n");
        } else {
            int shown = 0;
            for (CreditSaleReminderLineItem line : lines) {
                if (shown >= MAX_ITEM_LINES) {
                    break;
                }
                sb.append("• ")
                        .append(line.name() != null ? line.name() : "Item")
                        .append(" — ")
                        .append(formatMoney(line.lineTotal(), currency))
                        .append("\n");
                shown++;
            }
            int remaining = lines.size() - shown;
            if (remaining > 0) {
                sb.append("• and ").append(remaining).append(" more\n");
            }
        }

        sb.append("\nAmount: ").append(formatMoney(amount, currency));
        sb.append("\nRef: ").append(barcodeCode);
        sb.append("\n\nCheck your phone for the M-Pesa prompt to pay.");
        if (accountUrl != null && !accountUrl.isBlank()) {
            sb.append("\nView / pay: ").append(accountUrl.trim());
        }
        return sb.toString();
    }

    private static String formatMoney(BigDecimal amount, String currency) {
        BigDecimal scaled = amount == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : amount.setScale(2, RoundingMode.HALF_UP);
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.UK);
        nf.setMinimumFractionDigits(scaled.scale() > 0 && scaled.remainder(BigDecimal.ONE).signum() != 0 ? 2 : 0);
        nf.setMaximumFractionDigits(2);
        return currency + " " + nf.format(scaled);
    }
}
