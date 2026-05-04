package zelisline.ub.integrations.csvimport.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.integrations.csvimport.application.ImportJobRunner;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.integrations.import.jobs.worker.enabled", havingValue = "true")
public class ImportJobScheduler {

    private final ImportJobRunner importJobRunner;

    @Scheduled(
            fixedDelayString = "${app.integrations.import.jobs.worker.poll-interval-ms:4000}",
            initialDelayString = "${app.integrations.import.jobs.worker.initial-delay-ms:8000}"
    )
    public void tick() {
        try {
            importJobRunner.processNext();
        } catch (RuntimeException ex) {
            log.warn("Import job scheduler tick failed", ex);
        }
    }
}
