package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.CreditReminderRecord;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditReminderRecordRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.api.dto.CreditSaleReminderTestResponse;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.CustomerTabPaymentUrl;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Recurring balance reminder sweep for outstanding tab (credit) accounts.
 * Runs daily and sends a WhatsApp/SMS reminder to eligible accounts every N days
 * until the balance is paid, the customer opts out, or the max reminder count is reached.
 */
@Service
@RequiredArgsConstructor
public class OverdueDebtReminderService {

    private static final Logger log = LoggerFactory.getLogger(OverdueDebtReminderService.class);

    private final CreditAccountRepository creditAccountRepository;
    private final CreditReminderRecordRepository creditReminderRecordRepository;
    private final CustomerRepository customerRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final BusinessRepository businessRepository;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final Clock clock;

    @Value("${app.credits.reminders.interval-days:3}")
    private int intervalDays;

    @Value("${app.credits.reminders.min-balance:1.00}")
    private BigDecimal minBalance;

    @Value("${app.credits.reminders.max-count:5}")
    private int maxCount;

    @Value("${app.credits.reminders.zone:Africa/Nairobi}")
    private String zoneId;

    @Transactional
    public ReminderSweepReport sweep() {
        Instant staleBefore = clock.instant().minus(Duration.ofDays(Math.max(1, intervalDays)));
        List<CreditAccount> eligible = creditAccountRepository.findEligibleForBalanceReminder(
                minBalance, staleBefore, maxCount);

        int sent = 0;
        int skipped = 0;
        for (CreditAccount account : eligible) {
            DispatchOutcome outcome = dispatch(account);
            if ("skipped".equals(outcome.status())) {
                skipped++;
            } else {
                sent++;
            }
            account.setLastBalanceReminderAt(clock.instant());
            account.setBalanceReminderCount(account.getBalanceReminderCount() + 1);
            creditAccountRepository.save(account);

            CreditReminderRecord row = new CreditReminderRecord();
            row.setBusinessId(account.getBusinessId());
            row.setCreditAccountId(account.getId());
            row.setWeekBucket(currentDayBucket());
            row.setChannel(outcome.channel());
            row.setOutcome(outcome.status());
            row.setDetail(outcome.detail());
            creditReminderRecordRepository.save(row);
        }
        log.info("credits.balance_reminder.sweep accounts={} sent={} skipped={} intervalDays={} maxCount={}",
                eligible.size(), sent, skipped, intervalDays, maxCount);
        return new ReminderSweepReport(eligible.size(), sent, skipped, currentDayBucket());
    }

    /**
     * Staff-triggered balance reminder for one customer (WhatsApp and/or SMS).
     *
     * @param channelPref {@code auto} (WhatsApp then SMS), {@code whatsapp}, or {@code sms}
     */
    @Transactional
    public CreditSaleReminderTestResponse sendManualReminder(
            String businessId,
            String customerId,
            String channelPref
    ) {
        String channel = channelPref == null || channelPref.isBlank()
                ? "auto"
                : channelPref.trim().toLowerCase(Locale.ROOT);
        if (!channel.equals("auto") && !channel.equals("whatsapp") && !channel.equals("sms")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Channel must be auto, whatsapp, or sms");
        }

        CreditAccount account = creditAccountRepository
                .findByCustomerIdAndBusinessId(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credit account not found"));
        BigDecimal owed = account.getBalanceOwed() == null ? BigDecimal.ZERO : account.getBalanceOwed();
        if (owed.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer has no outstanding balance");
        }
        if (account.isRemindersOptOut()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Customer opted out of reminders");
        }

        Customer customer = customerRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));

        String phoneDigits = resolvePrimaryPhoneDigits(customerId);
        if (phoneDigits == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer has no phone number");
        }

        // Staff-triggered: allow send even if the automated reminders toggle is off.
        TenantMessagingConfig messaging = messagingSettingsService.resolveForTest(businessId);
        if (!messaging.secretsReadable()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    messaging.secretsReadError() != null
                            ? messaging.secretsReadError()
                            : "Messaging credentials are not readable");
        }
        if (!messaging.metaWhatsAppConfigured() && !messaging.smsConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Configure WhatsApp or SMS under Credit customers → Messaging");
        }

        Business business = businessRepository.findById(businessId).orElse(null);
        String currency = business != null && business.getCurrency() != null
                ? business.getCurrency().trim()
                : "KES";
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "our shop";
        String paymentUrl = CustomerTabPaymentUrl.build(
                messaging.paymentAccountUrl().isBlank()
                        ? "https://palmart.co.ke"
                        : messaging.paymentAccountUrl().trim(),
                phoneDigits);
        String message = buildMessage(customer.getName(), shopName, owed, currency, paymentUrl);

        CustomerMessageDispatcher.DeliveryResult delivery = switch (channel) {
            case "whatsapp" -> customerMessageDispatcher.deliverDirect(messaging, phoneDigits, message);
            case "sms" -> customerMessageDispatcher.deliverSmsOnly(messaging, phoneDigits, message);
            default -> customerMessageDispatcher.deliver(messaging, phoneDigits, message);
        };

        if ("sent".equals(delivery.outcome()) || "stub".equals(delivery.outcome())) {
            account.setLastBalanceReminderAt(clock.instant());
            account.setBalanceReminderCount(account.getBalanceReminderCount() + 1);
            creditAccountRepository.save(account);
        }

        log.info("credits.balance_reminder.manual customer={} channel={} outcome={} detail={}",
                customerId, delivery.channel(), delivery.outcome(), delivery.detail());

        var lookup = delivery.lookup();
        return new CreditSaleReminderTestResponse(
                messaging.enabled(),
                messaging.rapidApiConfigured(),
                messaging.metaWhatsAppConfigured(),
                messaging.smsConfigured(),
                lookup.skipped(),
                lookup.onWhatsApp(),
                lookup.detail(),
                delivery.channel(),
                delivery.outcome(),
                delivery.detail(),
                message);
    }

    DispatchOutcome dispatch(CreditAccount account) {
        TenantMessagingConfig messaging = messagingSettingsService.resolveForDispatch(account.getBusinessId());
        if (!messaging.enabled()) {
            return new DispatchOutcome("none", "skipped", "messaging_disabled");
        }

        Customer customer = customerRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(account.getCustomerId(), account.getBusinessId())
                .orElse(null);
        if (customer == null) {
            return new DispatchOutcome("none", "skipped", "customer_not_found");
        }

        String phoneDigits = resolvePrimaryPhoneDigits(account.getCustomerId());
        if (phoneDigits == null) {
            return new DispatchOutcome("none", "skipped", "no_phone");
        }

        Business business = businessRepository.findById(account.getBusinessId()).orElse(null);
        String currency = business != null && business.getCurrency() != null
                ? business.getCurrency().trim()
                : "KES";
        String shopName = business != null && business.getName() != null
                ? business.getName().trim()
                : "our shop";
        String paymentUrl = CustomerTabPaymentUrl.build(
                messaging.paymentAccountUrl().isBlank()
                        ? "https://palmart.co.ke"
                        : messaging.paymentAccountUrl().trim(),
                phoneDigits);
        String message = buildMessage(
                customer.getName(),
                shopName,
                account.getBalanceOwed(),
                currency,
                paymentUrl);

        CustomerMessageDispatcher.DeliveryResult delivery = customerMessageDispatcher.deliver(messaging, phoneDigits, message);
        log.info("credits.balance_reminder account={} customer={} channel={} outcome={} detail={}",
                account.getId(), account.getCustomerId(), delivery.channel(), delivery.outcome(), delivery.detail());
        return new DispatchOutcome(delivery.channel(), delivery.outcome(), delivery.detail());
    }

    private String buildMessage(String customerName, String shopName, BigDecimal balanceOwed, String currency, String paymentUrl) {
        String greeting = (customerName == null || customerName.isBlank()) ? "Hi" : "Hi " + customerName.trim();
        return greeting + ", you still owe " + formatMoney(balanceOwed, currency) + " at " + shopName + ".\n"
                + "Pay here: " + paymentUrl;
    }

    private String resolvePrimaryPhoneDigits(String customerId) {
        List<CustomerPhone> phones = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        if (phones.isEmpty()) {
            return null;
        }
        CustomerPhone pick = phones.stream().filter(CustomerPhone::isPrimary).findFirst().orElse(phones.getFirst());
        return StkPhoneNormalizer.normalize(pick.getPhone());
    }

    private String currentDayBucket() {
        return clock.instant().atZone(ZoneId.of(zoneId)).toLocalDate()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    private static String formatMoney(BigDecimal amount, String currency) {
        BigDecimal scaled = amount.setScale(2, RoundingMode.HALF_UP);
        java.text.NumberFormat nf = java.text.NumberFormat.getNumberInstance(Locale.UK);
        nf.setMinimumFractionDigits(scaled.scale() > 0 && scaled.remainder(BigDecimal.ONE).signum() != 0 ? 2 : 0);
        nf.setMaximumFractionDigits(2);
        return currency + " " + nf.format(scaled);
    }

    public record DispatchOutcome(String channel, String status, String detail) {
    }

    public record ReminderSweepReport(int candidates, int sent, int alreadySent, String dayBucket) {
    }
}
