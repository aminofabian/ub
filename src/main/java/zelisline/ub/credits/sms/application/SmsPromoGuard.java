package zelisline.ub.credits.sms.application;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.sms.domain.SmsMarketingStatus;
import zelisline.ub.credits.sms.domain.SmsPhones;
import zelisline.ub.messaging.application.SmsMessageLogService;

/**
 * The one place that decides whether a promotional SMS may go to a customer (SMS Hub,
 * ADR 0013): the customer must exist, have a textable number, have opted in, and be
 * under the shop's weekly limit. Campaigns and promotional automations both call this
 * immediately before sending, so a customer who opts out mid-campaign is not texted.
 * The sending window is checked by the caller, once per run.
 */
@Component
@RequiredArgsConstructor
public class SmsPromoGuard {

    public static final String SKIP_GONE = "Customer no longer exists";
    public static final String SKIP_NO_NUMBER = "No phone number";
    public static final String SKIP_MASKED = "Number hidden by M-Pesa";
    public static final String SKIP_NO_CONSENT = "Has not agreed to offers";
    public static final String SKIP_OPTED_OUT = "Opted out";
    public static final String SKIP_WEEKLY_CAP = "Already had an offer this week";
    public static final String SKIP_NO_CREDITS = "Out of SMS credits";

    private final SmsMessageLogService messageLog;

    /** {@code phoneDigits} is set when {@code allowed}; {@code reason} when not. */
    public record Decision(boolean allowed, String phoneDigits, String reason) {
        static Decision allow(String phoneDigits) {
            return new Decision(true, phoneDigits, null);
        }

        static Decision deny(String reason) {
            return new Decision(false, null, reason);
        }
    }

    /** Consent and number only: what an audience preview can know without the message log. */
    public static Decision eligibility(Customer customer, CustomerPhone phone) {
        if (customer == null || customer.getDeletedAt() != null || customer.getAnonymisedAt() != null) {
            return Decision.deny(SKIP_GONE);
        }
        if (phone == null) {
            return Decision.deny(SKIP_NO_NUMBER);
        }
        String digits = SmsPhones.textableDigits(phone);
        if (digits == null) {
            return Decision.deny(SmsPhones.masked(phone) ? SKIP_MASKED : SKIP_NO_NUMBER);
        }
        String status = customer.getSmsMarketingStatus();
        if (SmsMarketingStatus.OPTED_OUT.equals(status)) {
            return Decision.deny(SKIP_OPTED_OUT);
        }
        if (!SmsMarketingStatus.optedIn(status)) {
            return Decision.deny(SKIP_NO_CONSENT);
        }
        return Decision.allow(digits);
    }

    /** Full check at send time, including the weekly limit. */
    public Decision check(String businessId, Customer customer, CustomerPhone phone, int weeklyCap, Instant now) {
        Decision basic = eligibility(customer, phone);
        if (!basic.allowed()) {
            return basic;
        }
        long recent = messageLog.promotionalSince(
                businessId, basic.phoneDigits(), now.minus(Duration.ofDays(7)));
        if (recent >= Math.max(1, weeklyCap)) {
            return Decision.deny(SKIP_WEEKLY_CAP);
        }
        return basic;
    }
}
