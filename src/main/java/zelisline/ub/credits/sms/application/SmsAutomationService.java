package zelisline.ub.credits.sms.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.sms.domain.BusinessSmsHubSettings;
import zelisline.ub.credits.sms.domain.SmsSendWindow;
import zelisline.ub.credits.sms.repository.BusinessSmsHubSettingsRepository;
import zelisline.ub.messaging.application.SmsCreditsDepletedException;
import zelisline.ub.messaging.application.SmsMessageLogService;
import zelisline.ub.messaging.application.SmsSendContext;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;
import zelisline.ub.sales.domain.Sale;
import zelisline.ub.sales.repository.SaleRepository;

/**
 * Promotional SMS rules that run on their own (SMS Hub, ADR 0013): a thank-you after a
 * customer's first purchase and a win-back after a quiet spell. Both are marketing, so
 * both go through {@link SmsPromoGuard} (consent, weekly limit) and the sending window,
 * and each is sent at most once per customer per occasion (checked against the message
 * log by reference). A text that cannot go out now is dropped, never queued: a late
 * "thank you" or "we miss you" is worse than none.
 */
@Service
@RequiredArgsConstructor
public class SmsAutomationService {

    private static final Logger log = LoggerFactory.getLogger(SmsAutomationService.class);

    /** A sale older than this (a till that synced late) no longer earns a thank-you. */
    private static final Duration THANK_YOU_MAX_AGE = Duration.ofHours(2);
    /** A customer is eligible for win-back for this long after crossing the threshold. */
    private static final Duration WINBACK_SLACK = Duration.ofDays(7);
    private static final int WINBACK_DAILY_LIMIT = 200;
    private static final long SEND_PAUSE_MS = 60L;

    private final SmsHubSettingsService settingsService;
    private final BusinessSmsHubSettingsRepository settingsRepository;
    private final SmsPromoGuard promoGuard;
    private final SmsCampaignText campaignText;
    private final SmsAudienceService audienceService;
    private final CustomerRepository customerRepository;
    private final SaleRepository saleRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final SmsMessagingClient smsMessagingClient;
    private final SmsMessageLogService messageLog;

    /** Thank-you for a customer's first linked purchase. Safe to call for every sale. */
    public void thankYouForSale(String businessId, String saleId) {
        BusinessSmsHubSettings settings = settingsService.load(businessId);
        if (!settings.isThankYouEnabled()) {
            return;
        }
        Instant now = Instant.now();
        if (!SmsSendWindow.isOpen(now)) {
            return;
        }
        Sale sale = saleRepository.findByIdAndBusinessId(saleId, businessId).orElse(null);
        if (sale == null || sale.getCustomerId() == null || sale.getCustomerId().isBlank()) {
            return;
        }
        if (sale.getSoldAt() == null || sale.getSoldAt().isBefore(now.minus(THANK_YOU_MAX_AGE))) {
            return;
        }
        String customerId = sale.getCustomerId();
        List<Sale> history = saleRepository.findByBusinessIdAndCustomerIdOrderBySoldAtDesc(
                businessId, customerId, PageRequest.of(0, 2));
        if (history.size() != 1) {
            return;
        }
        Customer customer = customerRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .orElse(null);
        if (customer == null) {
            return;
        }
        TenantMessagingConfig messaging = messaging(businessId);
        if (messaging == null) {
            return;
        }
        String template = settings.getThankYouTemplate() != null
                ? settings.getThankYouTemplate()
                : SmsHubSettingsService.DEFAULT_THANK_YOU;
        Map<String, CustomerPhone> phones = audienceService.primaryPhones(List.of(customer));
        sendPromo(businessId, customer, phones.get(customer.getId()), template,
                "thankyou:" + customerId, settings, messaging, audienceService.shopName(businessId), now);
    }

    /**
     * Daily win-back pass over every shop that switched it on.
     *
     * @return number of texts sent
     */
    public int runWinback() {
        Instant now = Instant.now();
        if (!SmsSendWindow.isOpen(now)) {
            return 0;
        }
        int sent = 0;
        for (BusinessSmsHubSettings settings : settingsRepository.findByWinbackEnabledTrue()) {
            try {
                sent += winbackForBusiness(settings, now);
            } catch (RuntimeException ex) {
                log.error("sms_winback failed business={}: {}", settings.getBusinessId(), ex.getMessage());
            }
        }
        return sent;
    }

    private int winbackForBusiness(BusinessSmsHubSettings settings, Instant now) {
        String businessId = settings.getBusinessId();
        TenantMessagingConfig messaging = messaging(businessId);
        if (messaging == null) {
            return 0;
        }
        Instant newest = now.minus(Duration.ofDays(Math.max(7, settings.getWinbackDays())));
        Instant oldest = newest.minus(WINBACK_SLACK);

        Map<String, Instant> due = new HashMap<>();
        for (Object[] row : saleRepository.aggregatePurchaseStatsByCustomer(businessId)) {
            if (row == null || row[0] == null || !(row[4] instanceof Instant last)) {
                continue;
            }
            if (!last.isAfter(newest) && last.isAfter(oldest)) {
                due.put(String.valueOf(row[0]), last);
            }
        }
        if (due.isEmpty()) {
            return 0;
        }
        List<String> ids = new ArrayList<>(due.keySet());
        List<Customer> customers = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += 500) {
            customers.addAll(customerRepository.findByIdInAndBusinessId(
                    ids.subList(from, Math.min(ids.size(), from + 500)), businessId));
        }
        Map<String, CustomerPhone> phones = audienceService.primaryPhones(customers);
        String template = settings.getWinbackTemplate() != null
                ? settings.getWinbackTemplate()
                : SmsHubSettingsService.DEFAULT_WINBACK;
        String shopName = audienceService.shopName(businessId);

        int sent = 0;
        for (Customer customer : customers) {
            if (sent >= WINBACK_DAILY_LIMIT) {
                break;
            }
            // One win-back per quiet spell: the reference carries the last purchase day.
            long lastDay = due.get(customer.getId()).getEpochSecond() / 86_400L;
            String reference = "winback:" + customer.getId() + ":" + lastDay;
            Outcome outcome = sendPromo(businessId, customer, phones.get(customer.getId()), template,
                    reference, settings, messaging, shopName, now);
            if (outcome == Outcome.OUT_OF_CREDITS) {
                break;
            }
            if (outcome == Outcome.SENT) {
                sent++;
                pause();
            }
        }
        if (sent > 0) {
            log.info("sms_winback business={} sent={}", businessId, sent);
        }
        return sent;
    }

    private enum Outcome {
        SENT, SKIPPED, OUT_OF_CREDITS
    }

    private Outcome sendPromo(
            String businessId,
            Customer customer,
            CustomerPhone phone,
            String template,
            String reference,
            BusinessSmsHubSettings settings,
            TenantMessagingConfig messaging,
            String shopName,
            Instant now
    ) {
        if (messageLog.alreadySent(businessId, reference)) {
            return Outcome.SKIPPED;
        }
        SmsPromoGuard.Decision decision =
                promoGuard.check(businessId, customer, phone, settings.getPromoWeeklyCap(), now);
        if (!decision.allowed()) {
            return Outcome.SKIPPED;
        }
        String text = campaignText.render(template, customer.getName(), shopName);
        try (SmsSendContext.Scope ignored = SmsSendContext.promotional(customer.getId(), null)) {
            SmsMessagingClient.SendResult result = smsMessagingClient.sendText(
                    messaging, "+" + decision.phoneDigits(), text, SmsSendReason.AUTOMATION, reference);
            return result.sent() ? Outcome.SENT : Outcome.SKIPPED;
        } catch (SmsCreditsDepletedException ex) {
            return Outcome.OUT_OF_CREDITS;
        }
    }

    /** Messaging config when SMS can actually be sent for this shop, else null. */
    private TenantMessagingConfig messaging(String businessId) {
        TenantMessagingConfig messaging =
                messagingSettingsService.resolveForTest(businessId, SmsSendReason.AUTOMATION);
        return messaging.secretsReadable() && messaging.smsConfigured() ? messaging : null;
    }

    private static void pause() {
        try {
            Thread.sleep(SEND_PAUSE_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
