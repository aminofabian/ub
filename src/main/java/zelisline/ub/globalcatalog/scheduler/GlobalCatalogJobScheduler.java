package zelisline.ub.globalcatalog.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.globalcatalog.application.GlobalCatalogJobRunner;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.global-catalog.jobs.worker.enabled", havingValue = "true")
public class GlobalCatalogJobScheduler {

    private final GlobalCatalogJobRunner globalCatalogJobRunner;

    @Scheduled(
            fixedDelayString = "${app.global-catalog.jobs.worker.poll-interval-ms:3000}",
            initialDelayString = "${app.global-catalog.jobs.worker.initial-delay-ms:5000}"
    )
    public void tick() {
        try {
            globalCatalogJobRunner.processNext();
        } catch (RuntimeException ex) {
            log.warn("Global catalog job scheduler tick failed", ex);
        }
    }
}
