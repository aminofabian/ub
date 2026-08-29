package zelisline.ub.messaging.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.messaging.domain.SmsSendReason;

/**
 * Single choke point for SMS metering, wired into {@code SmsMessagingClient.sendText}
 * (SMS_CREDITS_SCOPE.md §8). Platform-scoped sends (no business) and stub sends
 * (provider not configured) never reach here; the kill switch disables metering.
 *
 * <p>Deduct-on-success: {@link #checkBeforeSend} blocks at zero, then
 * {@link #debitOnSuccess} atomically consumes one credit after the provider returns
 * 2xx. A concurrent send may win the last credit between the two steps; in that
 * case the SMS has already gone out and we log instead of failing the caller.
 */
@Component
@RequiredArgsConstructor
public class SmsCreditGuard {

    private static final Logger log = LoggerFactory.getLogger(SmsCreditGuard.class);

    private final SmsCreditService creditService;
    private final SmsCreditSettingsService settingsService;

    /**
     * True when this send must be metered: tenant-scoped and the platform kill
     * switch is on.
     */
    public boolean meteringActive(String businessId) {
        return businessId != null && !businessId.isBlank() && settingsService.isEnabled();
    }

    /** Pre-flight: throw 402 when the tenant has nothing left to spend. */
    public void checkBeforeSend(String businessId, SmsSendReason reason, String referenceId) {
        if (!meteringActive(businessId)) {
            return;
        }
        creditService.requireAvailable(businessId, reason, referenceId);
    }

    /** Post-success: atomically consume one credit. Never throws to the caller. */
    public void debitOnSuccess(String businessId, SmsSendReason reason, String referenceId) {
        if (!meteringActive(businessId)) {
            return;
        }
        try {
            creditService.debit(businessId, reason, referenceId);
        } catch (SmsCreditsDepletedException ex) {
            log.warn(
                    "sms_credits race: last credit consumed between check and debit business={} reason={}",
                    businessId, reason);
        }
    }
}
