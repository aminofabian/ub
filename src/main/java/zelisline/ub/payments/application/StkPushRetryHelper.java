package zelisline.ub.payments.application;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Orchestrates STK retries when KopoKopo/Safaricom still holds a pending prompt
 * for the phone. Polls the gateway (with short backoff) before initiating again —
 * cancelling local rows alone does not clear the M-Pesa lock.
 */
@Service
@RequiredArgsConstructor
public class StkPushRetryHelper {

    private static final Logger log = LoggerFactory.getLogger(StkPushRetryHelper.class);

    private static final int PRE_CLEAR_ATTEMPTS = 3;
    private static final long PRE_CLEAR_DELAY_MS = 1_500L;
    private static final int POST_REJECT_ATTEMPTS = 3;
    private static final long POST_REJECT_BASE_DELAY_MS = 1_500L;
    /** Brief pause after gateway reports Failed so Safaricom can release the MSISDN lock. */
    private static final long AFTER_FAILED_RELEASE_MS = 1_200L;

    private final GatewayStkPushService gatewayStkPushService;
    private final PaymentGatewayStkService paymentGatewayStkService;

    public PaymentGatewayStkService.StkPushOutcome initiateAfterClearingPhone(
            String businessId,
            String preferredConfigId,
            String phone,
            BigDecimal amount,
            String reference,
            String description
    ) {
        awaitPhoneClear(businessId, phone, PRE_CLEAR_ATTEMPTS, PRE_CLEAR_DELAY_MS);
        gatewayStkPushService.cancelPendingForPhone(
                businessId,
                phone,
                "Replaced by a new M-Pesa prompt");

        PaymentGatewayStkService.StkPushOutcome outcome = paymentGatewayStkService.initiate(
                businessId, preferredConfigId, phone, amount, reference, description);

        if (outcome.accepted() || !GatewayStkPushService.isKopokopoPendingPhoneError(outcome.message())) {
            return maybeRewritePendingMessage(outcome);
        }

        log.info("STK pending-phone reject for business={} phone={} — polling gateway then retrying",
                businessId, phone);

        for (int attempt = 0; attempt < POST_REJECT_ATTEMPTS; attempt++) {
            GatewayStkPushService.PhoneClearResult settled =
                    gatewayStkPushService.settleRecentPushesForPhone(businessId, phone);
            sleep(POST_REJECT_BASE_DELAY_MS + (attempt * 1_000L));
            if (!settled.hasGatewayPending()) {
                sleep(AFTER_FAILED_RELEASE_MS);
            }
            gatewayStkPushService.cancelPendingForPhone(
                    businessId,
                    phone,
                    "Cleared after gateway rejected duplicate pending prompt");

            String retryRef = reference + "-r" + (attempt + 1);
            outcome = paymentGatewayStkService.initiate(
                    businessId, preferredConfigId, phone, amount, retryRef, description);
            if (outcome.accepted()) {
                return outcome;
            }
            if (!GatewayStkPushService.isKopokopoPendingPhoneError(outcome.message())) {
                return outcome;
            }
            log.info("STK pending-phone still blocked attempt={}/{} business={}",
                    attempt + 1, POST_REJECT_ATTEMPTS, businessId);
        }

        return PaymentGatewayStkService.StkPushOutcome.rejected(
                outcome.gatewayType(),
                outcome.responseCode() != null ? outcome.responseCode() : "PENDING_PHONE",
                GatewayStkPushService.pendingPhoneUserMessage());
    }

    private void awaitPhoneClear(String businessId, String phone, int attempts, long delayMs) {
        for (int i = 0; i < attempts; i++) {
            GatewayStkPushService.PhoneClearResult settled =
                    gatewayStkPushService.settleRecentPushesForPhone(businessId, phone);
            if (!settled.hasGatewayPending()) {
                if (settled.terminalUpdates() > 0) {
                    sleep(AFTER_FAILED_RELEASE_MS);
                }
                return;
            }
            if (i < attempts - 1) {
                sleep(delayMs);
            }
        }
        gatewayStkPushService.reconcilePendingForPhone(businessId, phone);
        gatewayStkPushService.expireStalePendingForPhone(businessId, phone);
    }

    private static PaymentGatewayStkService.StkPushOutcome maybeRewritePendingMessage(
            PaymentGatewayStkService.StkPushOutcome outcome
    ) {
        if (!outcome.accepted() && GatewayStkPushService.isKopokopoPendingPhoneError(outcome.message())) {
            return PaymentGatewayStkService.StkPushOutcome.rejected(
                    outcome.gatewayType(),
                    outcome.responseCode(),
                    GatewayStkPushService.pendingPhoneUserMessage());
        }
        return outcome;
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
