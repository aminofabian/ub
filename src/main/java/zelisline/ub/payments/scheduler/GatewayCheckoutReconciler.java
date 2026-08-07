package zelisline.ub.payments.scheduler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import zelisline.ub.payments.application.GatewayCheckoutService;

/**
 * Reconciles PENDING hosted checkouts when webhooks are delayed or the shopper
 * abandons the Paystack page (fallback: server-side verify by reference).
 *
 * <p>Mirrors {@code GatewayStkPushPoller}: checkouts older than the cutoff are
 * verified; after {@code maxAttempts} they are marked {@code CANCELLED}.
 */
@Component
public class GatewayCheckoutReconciler {

    private static final Logger log = LoggerFactory.getLogger(GatewayCheckoutReconciler.class);

    private final GatewayCheckoutService checkoutService;

    @Value("${app.payments.checkout.reconcile.max-age-minutes:45}")
    private int maxAgeMinutes;

    @Value("${app.payments.checkout.reconcile.max-attempts:30}")
    private int maxAttempts;

    public GatewayCheckoutReconciler(GatewayCheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @Scheduled(fixedDelayString = "${app.payments.checkout.reconcile.interval-ms:60000}")
    public void reconcilePending() {
        Instant cutoff = Instant.now().minus(maxAgeMinutes, ChronoUnit.MINUTES);
        try {
            checkoutService.reconcileStalePending(cutoff, cutoff, maxAttempts);
        } catch (Exception e) {
            log.warn("Paystack checkout reconcile run failed: {}", e.getMessage());
        }
    }
}
