package zelisline.ub.messaging.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
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

        String message = buildMessage(
                shopName,
                event.barcodeCode(),
                event.grandTotal(),
                event.lineCount(),
                currency);

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
            String currency
    ) {
        int count = Math.max(1, lineCount);
        String items = count == 1 ? "1 item" : count + " items";
        return "Hi,\n\n"
                + "Your bill at " + shopName + " is ready (" + items + ").\n"
                + "Amount: " + formatMoney(amount, currency) + "\n"
                + "Ref: " + barcodeCode + "\n\n"
                + "Check your phone for the M-Pesa prompt to pay.";
    }

    private static String formatMoney(BigDecimal amount, String currency) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.UK);
        nf.setMinimumFractionDigits(scaled.scale() > 0 && scaled.remainder(BigDecimal.ONE).signum() != 0 ? 2 : 0);
        nf.setMaximumFractionDigits(2);
        return currency + " " + nf.format(scaled);
    }
}
