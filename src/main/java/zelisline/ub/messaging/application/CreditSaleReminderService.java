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
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.CreditSaleReminderDispatch;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditSaleReminderDispatchRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.infrastructure.MetaWhatsAppMessagingClient;
import zelisline.ub.messaging.infrastructure.RapidApiWhatsAppLookupClient;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;
import zelisline.ub.notifications.application.NotificationService;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class CreditSaleReminderService {

    private static final Logger log = LoggerFactory.getLogger(CreditSaleReminderService.class);

    static final String NOTIFICATION_TYPE = "credit_sale.reminder";

    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CreditSaleReminderDispatchRepository dispatchRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final RapidApiWhatsAppLookupClient whatsAppLookupClient;
    private final MetaWhatsAppMessagingClient metaWhatsAppClient;
    private final SmsMessagingClient smsMessagingClient;
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
        String paymentUrl = messaging.paymentAccountUrl().trim();
        String message = buildMessage(event.itemCount(), event.creditAmount(), currency, paymentUrl);

        pushInAppNotification(event, customer, message, paymentUrl, currency);

        ExternalDeliveryAttempt delivery = deliverExternalMessage(messaging, phoneDigits, message);
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
        String message = buildMessage(
                2,
                new BigDecimal("100.00"),
                "KES",
                messaging.paymentAccountUrl().isBlank()
                        ? "https://palmart.co.ke/shop/account"
                        : messaging.paymentAccountUrl());

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

        ExternalDeliveryAttempt attempt = deliverExternalMessage(messaging, phoneDigits, message);
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

    private ExternalDeliveryAttempt deliverExternalMessage(
            TenantMessagingConfig messaging,
            String phoneDigits,
            String message
    ) {
        String e164 = "+" + phoneDigits;
        String channel;
        String outcome;
        String detail;

        var lookup = whatsAppLookupClient.lookup(messaging, e164);
        if (lookup.onWhatsApp()) {
            var send = metaWhatsAppClient.sendText(messaging, phoneDigits, message);
            if (send.sent()) {
                channel = send.channel();
                outcome = "sent";
                detail = send.detail();
            } else if (send.skipped()) {
                var sms = smsMessagingClient.sendText(messaging, e164, message);
                channel = sms.channel();
                outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
                detail = "whatsapp_skipped:" + send.detail() + ";" + sms.detail();
            } else {
                var sms = smsMessagingClient.sendText(messaging, e164, message);
                channel = sms.sent() || sms.stub() ? sms.channel() : "sms";
                outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
                detail = "whatsapp_failed:" + send.detail() + ";" + sms.detail();
            }
        } else if (lookup.skipped()) {
            var sms = smsMessagingClient.sendText(messaging, e164, message);
            channel = sms.channel();
            outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
            detail = "lookup_skipped:" + lookup.detail() + ";" + sms.detail();
        } else {
            var sms = smsMessagingClient.sendText(messaging, e164, message);
            channel = sms.channel();
            outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
            detail = "not_on_whatsapp:" + lookup.detail() + ";" + sms.detail();
        }
        return new ExternalDeliveryAttempt(lookup, channel, outcome, detail);
    }

    private record ExternalDeliveryAttempt(
            RapidApiWhatsAppLookupClient.LookupResult lookup,
            String channel,
            String outcome,
            String detail
    ) {
    }

    private void pushInAppNotification(
            CreditSaleReminderEvent event,
            Customer customer,
            String message,
            String paymentUrl,
            String currency
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
        payload.put("currency", currency);
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

    static String buildMessage(int itemCount, BigDecimal amount, String currency, String paymentUrl) {
        int items = Math.max(1, itemCount);
        String itemLabel = items == 1 ? "item" : "items";
        String money = formatMoney(amount, currency);
        return "You took " + items + " " + itemLabel + " on credit worth " + money + ".\n"
                + "Pay here: " + paymentUrl;
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
