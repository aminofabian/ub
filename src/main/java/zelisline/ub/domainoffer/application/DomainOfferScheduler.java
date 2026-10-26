package zelisline.ub.domainoffer.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Sends the next due touch for the running domain + theme offer. One shop can
 * be mid-payment while another is on touch 2 — the cadence is per order, not a
 * global blast.
 */
@Component
@RequiredArgsConstructor
public class DomainOfferScheduler {

    private static final Logger log = LoggerFactory.getLogger(DomainOfferScheduler.class);

    private final DomainOfferService domainOfferService;

    @Value("${app.domain-offer.scheduler-enabled:true}")
    private boolean enabled;

    @Scheduled(
            fixedDelayString = "${app.domain-offer.scheduler-fixed-delay-ms:60000}",
            initialDelayString = "${app.domain-offer.scheduler-initial-delay-ms:30000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            int sent = domainOfferService.sendDueTouches();
            if (sent > 0) {
                log.info("Domain offer scheduler sent {} touch(es)", sent);
            }
        } catch (Exception ex) {
            log.warn("Domain offer scheduler tick failed: {}", ex.toString());
        }
    }
}
