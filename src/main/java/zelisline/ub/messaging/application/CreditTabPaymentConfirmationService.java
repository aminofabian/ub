package zelisline.ub.messaging.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class CreditTabPaymentConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(CreditTabPaymentConfirmationService.class);

    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final BusinessRepository businessRepository;

    public void dispatch(CreditTabPaymentConfirmationEvent event) {
        TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(event.businessId());
        if (!messaging.secretsReadable()) {
            log.info(
                    "Skip tab payment confirmation — messaging secrets unreadable reference={}",
                    event.referenceId());
            return;
        }
        if (!messaging.smsConfigured() && !messaging.metaWhatsAppConfigured()) {
            log.info(
                    "Skip tab payment confirmation — no SMS or WhatsApp configured reference={}",
                    event.referenceId());
            return;
        }

        String phoneDigits = event.phoneDigits();
        if (phoneDigits == null || phoneDigits.isBlank()) {
            phoneDigits = resolvePrimaryPhoneDigits(event.customerId());
        }
        if (phoneDigits == null) {
            log.info("Skip tab payment confirmation — no phone reference={}", event.referenceId());
            return;
        }

        Business business = businessRepository.findById(event.businessId()).orElse(null);
        String currency = business != null && business.getCurrency() != null
                ? business.getCurrency().trim()
                : "KES";
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "our shop";

        Customer customer = customerRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(event.customerId(), event.businessId())
                .orElse(null);
        String customerName = customer != null ? customer.getName() : null;

        BigDecimal paid = event.amountPaid() != null ? event.amountPaid() : BigDecimal.ZERO;
        BigDecimal remaining = event.balanceRemaining() != null ? event.balanceRemaining() : BigDecimal.ZERO;
        String paymentUrl = remaining.signum() > 0
                ? CustomerTabPaymentUrl.build(messaging.paymentAccountUrl(), phoneDigits)
                : null;
        String message = buildMessage(
                customerName, shopName, paid, remaining, currency, paymentUrl, event.paymentMethod());

        CustomerMessageDispatcher.DeliveryResult delivery =
                customerMessageDispatcher.deliver(messaging, phoneDigits, message);
        log.info(
                "credit_tab_payment_confirmation reference={} customer={} method={} channel={} outcome={} detail={}",
                event.referenceId(),
                event.customerId(),
                event.paymentMethod(),
                delivery.channel(),
                delivery.outcome(),
                delivery.detail());
    }

    static String buildMessage(
            String customerName,
            String shopName,
            BigDecimal amountPaid,
            BigDecimal balanceRemaining,
            String currency,
            String paymentUrl
    ) {
        return buildMessage(
                customerName, shopName, amountPaid, balanceRemaining, currency, paymentUrl, null);
    }

    static String buildMessage(
            String customerName,
            String shopName,
            BigDecimal amountPaid,
            BigDecimal balanceRemaining,
            String currency,
            String paymentUrl,
            String paymentMethod
    ) {
        StringBuilder sb = new StringBuilder();
        String greeting = (customerName == null || customerName.isBlank()) ? "Hi" : "Hi " + customerName.trim();
        sb.append(greeting).append(",\n\n");
        sb.append("We received your ")
                .append(paymentPhrase(paymentMethod))
                .append(" of ")
                .append(formatMoney(amountPaid, currency))
                .append(" at ")
                .append(shopName)
                .append(".");

        BigDecimal remaining = balanceRemaining != null ? balanceRemaining : BigDecimal.ZERO;
        if (remaining.signum() > 0) {
            sb.append("\nRemaining tab balance: ").append(formatMoney(remaining, currency));
            if (paymentUrl != null && !paymentUrl.isBlank()) {
                sb.append("\n\nPay here: ").append(paymentUrl);
            }
        } else {
            sb.append("\nYour tab is now fully paid.");
        }
        sb.append("\n\nThank you!");
        return sb.toString();
    }

    /** "payment" / "M-Pesa payment" / "cash payment" — safe to drop into a sentence. */
    private static String paymentPhrase(String paymentMethod) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "payment";
        }
        String method = paymentMethod.trim();
        return method.toLowerCase(Locale.ROOT).endsWith("payment") ? method : method + " payment";
    }

    private String resolvePrimaryPhoneDigits(String customerId) {
        List<CustomerPhone> phones = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        if (phones.isEmpty()) {
            return null;
        }
        CustomerPhone pick = phones.stream().filter(CustomerPhone::isPrimary).findFirst().orElse(phones.getFirst());
        return StkPhoneNormalizer.normalize(pick.getPhone());
    }

    private static String formatMoney(BigDecimal amount, String currency) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.UK);
        nf.setMinimumFractionDigits(scaled.scale() > 0 && scaled.remainder(BigDecimal.ONE).signum() != 0 ? 2 : 0);
        nf.setMaximumFractionDigits(2);
        return currency + " " + nf.format(scaled);
    }
}
