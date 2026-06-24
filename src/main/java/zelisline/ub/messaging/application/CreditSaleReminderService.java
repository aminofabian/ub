package zelisline.ub.messaging.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.CreditSaleReminderDispatch;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditSaleReminderDispatchRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.notifications.application.NotificationService;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class CreditSaleReminderService {

    private static final Logger log = LoggerFactory.getLogger(CreditSaleReminderService.class);

    static final String NOTIFICATION_TYPE = "credit_sale.reminder";
    static final int MAX_ITEM_LINES = 5;

    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CreditSaleReminderDispatchRepository dispatchRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final ObjectMapper objectMapper;

    @Transactional
    public void dispatch(CreditSaleReminderEvent event) {
        TenantMessagingConfig messaging = messagingSettingsService.resolveForDispatch(event.businessId());
        if (!messaging.enabled()) {
            return;
        }
        if (dispatchRepository.existsBySaleId(event.saleId())) {
            return;
        }

        Customer customer = customerRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(event.customerId(), event.businessId())
                .orElse(null);
        if (customer == null) {
            saveDispatch(event, "none", "skipped", "customer_not_found", null);
            return;
        }

        Optional<CreditAccount> creditAccount = creditAccountRepository.findByCustomerIdAndBusinessId(
                event.customerId(), event.businessId());
        if (creditAccount.map(CreditAccount::isRemindersOptOut).orElse(false)) {
            saveDispatch(event, "none", "skipped", "reminders_opt_out", null);
            return;
        }

        String phoneDigits = resolvePrimaryPhoneDigits(event.customerId());
        if (phoneDigits == null) {
            saveDispatch(event, "none", "skipped", "no_phone", null);
            return;
        }

        Business business = businessRepository.findById(event.businessId()).orElse(null);
        String currency = business != null && business.getCurrency() != null
                ? business.getCurrency().trim()
                : "KES";
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "our shop";
        String paymentUrl = messaging.paymentAccountUrl().trim();
        BigDecimal balanceOwed = event.balanceOwed() != null ? event.balanceOwed() : BigDecimal.ZERO;
        String message = buildMessage(
                customer.getName(),
                shopName,
                event.items(),
                event.itemCount(),
                event.creditAmount(),
                balanceOwed,
                currency,
                paymentUrl);

        pushInAppNotification(event, customer, message, paymentUrl, currency, balanceOwed);

        CustomerMessageDispatcher.DeliveryResult delivery = customerMessageDispatcher.deliver(messaging, phoneDigits, message);
        saveDispatch(event, delivery.channel(), delivery.outcome(), delivery.detail(), message);
        log.info("credit_sale_reminder sale={} customer={} channel={} outcome={} detail={}",
                event.saleId(), event.customerId(), delivery.channel(), delivery.outcome(), delivery.detail());
    }

    /**
     * Synchronous test send for admin settings (returns provider outcome in the HTTP response).
     */
    public zelisline.ub.credits.api.dto.CreditSaleReminderTestResponse sendTest(
            String businessId,
            String rawPhone
    ) {
        TenantMessagingConfig messaging = messagingSettingsService.resolveForDispatch(businessId);
        String paymentUrl = messaging.paymentAccountUrl().isBlank()
                ? "https://palmart.co.ke/shop/account"
                : messaging.paymentAccountUrl();
        List<CreditSaleReminderLineItem> dummyItems = List.of(
                new CreditSaleReminderLineItem("Sugar 2kg", new BigDecimal("2"), new BigDecimal("240.00")),
                new CreditSaleReminderLineItem("Milk 1L", BigDecimal.ONE, new BigDecimal("65.00")));
        String message = buildMessage(
                "Jane",
                "Mama's Kiosk",
                dummyItems,
                dummyItems.size(),
                new BigDecimal("305.00"),
                new BigDecimal("1240.00"),
                "KES",
                paymentUrl);

        if (!messaging.enabled()) {
            return new zelisline.ub.credits.api.dto.CreditSaleReminderTestResponse(
                    false,
                    messaging.rapidApiConfigured(),
                    messaging.metaWhatsAppConfigured(),
                    messaging.smsConfigured(),
                    false,
                    false,
                    "reminders_disabled",
                    "none",
                    "skipped",
                    "Enable “Send reminders after credit (tab) sales”, save, then retry.",
                    message);
        }

        String phoneDigits = StkPhoneNormalizer.normalize(rawPhone);
        if (phoneDigits == null) {
            return new zelisline.ub.credits.api.dto.CreditSaleReminderTestResponse(
                    true,
                    messaging.rapidApiConfigured(),
                    messaging.metaWhatsAppConfigured(),
                    messaging.smsConfigured(),
                    false,
                    false,
                    "invalid_phone",
                    "none",
                    "failed",
                    "Use a Kenyan number e.g. 0712345678 or 254712345678",
                    message);
        }

        CustomerMessageDispatcher.DeliveryResult attempt = customerMessageDispatcher.deliver(messaging, phoneDigits, message);
        return new zelisline.ub.credits.api.dto.CreditSaleReminderTestResponse(
                true,
                messaging.rapidApiConfigured(),
                messaging.metaWhatsAppConfigured(),
                messaging.smsConfigured(),
                attempt.lookup().skipped(),
                attempt.lookup().onWhatsApp(),
                attempt.lookup().detail(),
                attempt.channel(),
                attempt.outcome(),
                attempt.detail(),
                message);
    }

    private void pushInAppNotification(
            CreditSaleReminderEvent event,
            Customer customer,
            String message,
            String paymentUrl,
            String currency,
            BigDecimal balanceOwed
    ) {
        String userId = resolveShopperUserId(event.businessId(), customer);
        if (userId == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "Credit purchase");
        payload.put("body", message);
        payload.put("paymentUrl", paymentUrl);
        payload.put("saleId", event.saleId());
        payload.put("customerId", event.customerId());
        payload.put("itemCount", event.itemCount());
        payload.put("amount", event.creditAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        payload.put("balanceOwed", balanceOwed.setScale(2, RoundingMode.HALF_UP).toPlainString());
        payload.put("currency", currency);
        if (event.items() != null && !event.items().isEmpty()) {
            payload.put("items", event.items());
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        String dedupe = "credit_sale_reminder:" + event.saleId();
        notificationService.tryInsertDedupeForUser(
                event.businessId(), userId, NOTIFICATION_TYPE, dedupe, json);
    }

    private String resolveShopperUserId(String businessId, Customer customer) {
        String email = customer.getEmail();
        if (email == null || email.isBlank()) {
            return null;
        }
        String norm = email.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByBusinessIdAndEmailAndDeletedAtIsNull(businessId, norm)
                .map(u -> u.getId())
                .orElse(null);
    }

    private String resolvePrimaryPhoneDigits(String customerId) {
        List<CustomerPhone> phones = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        if (phones.isEmpty()) {
            return null;
        }
        CustomerPhone pick = phones.stream().filter(CustomerPhone::isPrimary).findFirst().orElse(phones.getFirst());
        return StkPhoneNormalizer.normalize(pick.getPhone());
    }

    static String buildMessage(
            String customerName,
            String shopName,
            List<CreditSaleReminderLineItem> items,
            int itemCount,
            BigDecimal amount,
            BigDecimal balanceOwed,
            String currency,
            String paymentUrl
    ) {
        StringBuilder sb = new StringBuilder();
        String greeting = (customerName == null || customerName.isBlank()) ? "Hi" : "Hi " + customerName.trim();
        sb.append(greeting).append(",\n\n");
        sb.append("You took on credit at ").append(shopName).append(":\n");

        List<CreditSaleReminderLineItem> lines = items != null ? items : List.of();
        if (lines.isEmpty()) {
            int count = Math.max(1, itemCount);
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

        sb.append("\nThis sale: ").append(formatMoney(amount, currency));
        if (balanceOwed != null && balanceOwed.signum() > 0) {
            sb.append("\nTotal tab: ").append(formatMoney(balanceOwed, currency));
        }
        sb.append("\n\nPay here: ").append(paymentUrl);
        return sb.toString();
    }

    private static String formatMoney(BigDecimal amount, String currency) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.UK);
        nf.setMinimumFractionDigits(scaled.scale() > 0 && scaled.remainder(BigDecimal.ONE).signum() != 0 ? 2 : 0);
        nf.setMaximumFractionDigits(2);
        return currency + " " + nf.format(scaled);
    }

    private void saveDispatch(
            CreditSaleReminderEvent event,
            String channel,
            String outcome,
            String detail,
            String messagePreview
    ) {
        CreditSaleReminderDispatch row = new CreditSaleReminderDispatch();
        row.setBusinessId(event.businessId());
        row.setSaleId(event.saleId());
        row.setCustomerId(event.customerId());
        row.setChannel(channel);
        row.setOutcome(outcome);
        row.setDetail(truncate(detail, 500));
        row.setMessagePreview(truncate(messagePreview, 500));
        dispatchRepository.save(row);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }
}
