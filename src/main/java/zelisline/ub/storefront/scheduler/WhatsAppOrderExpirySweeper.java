package zelisline.ub.storefront.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.storefront.application.WhatsAppOrderExpiryService;

/**
 * Releases stock for WhatsApp orders never confirmed within their window
 * (scope §11, Phase 3). Runs every 5 minutes; each sweep is idempotent —
 * expired orders are skipped once {@code handoff_state} is 'expired'.
 */
@Component
@RequiredArgsConstructor
public class WhatsAppOrderExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppOrderExpirySweeper.class);

    private final WhatsAppOrderExpiryService expiryService;

    @Scheduled(
            fixedDelayString = "${app.storefront.whatsapp-expiry.sweep-ms:300000}",
            initialDelayString = "${app.storefront.whatsapp-expiry.sweep-initial-ms:120000}")
    public void sweep() {
        try {
            int released = expiryService.sweepExpired();
            if (released > 0) {
                log.info("WhatsApp order expiry: released stock for {} order(s)", released);
            }
        } catch (RuntimeException e) {
            log.warn("WhatsApp order expiry sweep failed: {}", e.getMessage());
        }
    }
}
