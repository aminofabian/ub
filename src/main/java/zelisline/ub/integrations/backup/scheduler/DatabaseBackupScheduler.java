package zelisline.ub.integrations.backup.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.integrations.backup.application.DatabaseBackupOrchestrator;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnBean(DatabaseBackupOrchestrator.class)
@ConditionalOnProperty(name = "app.integrations.backup.scheduler.enabled", havingValue = "true")
public class DatabaseBackupScheduler {

    private final DatabaseBackupOrchestrator orchestrator;

    @Scheduled(
            cron = "${app.integrations.backup.scheduler.cron:0 0 3 * * *}",
            zone = "${app.integrations.backup.scheduler.zone:UTC}")
    public void tick() {
        try {
            orchestrator.runBackup();
        } catch (RuntimeException ex) {
            log.warn("Scheduled database backup failed", ex);
        }
    }
}
