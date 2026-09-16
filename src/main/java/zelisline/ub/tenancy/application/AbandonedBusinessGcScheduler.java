package zelisline.ub.tenancy.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Daily soft-GC of ownerless tenants left behind by abandoned create-business.
 */
@Component
@RequiredArgsConstructor
public class AbandonedBusinessGcScheduler {

    private static final Logger log = LoggerFactory.getLogger(AbandonedBusinessGcScheduler.class);

    private final AbandonedBusinessGcService abandonedBusinessGcService;

    @Scheduled(
            cron = "${app.tenancy.abandoned-business-gc-cron:0 20 3 * * *}",
            zone = "Africa/Nairobi")
    public void tick() {
        try {
            int purged = abandonedBusinessGcService.sweep();
            if (purged > 0) {
                log.debug("Abandoned business GC tick purged {}", purged);
            }
        } catch (RuntimeException ex) {
            log.error("Abandoned business GC tick failed: {}", ex.getMessage());
        }
    }
}
