package zelisline.ub.notifications.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.application.Phase7ApNotificationService;

/** Phase 7 Slice 6 — daily overdue AP inbox sweep (disabled unless explicitly enabled). */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.phase7.notifications.enabled", havingValue = "true")
public class Phase7NotificationScheduler {

    private final Phase7ApNotificationService phase7ApNotificationService;

    @Scheduled(cron = "${app.phase7.notifications.overdue-ap.cron:0 15 8 * * *}", zone = "${app.phase7.notifications.zone:UTC}")
    public void tickOverdueAp() {
        phase7ApNotificationService.scanAllTenants();
    }
}
