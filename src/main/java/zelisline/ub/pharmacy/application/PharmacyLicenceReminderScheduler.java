package zelisline.ub.pharmacy.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Daily licence-expiry sweep. Off unless {@code app.notifications.pharmacy.licence.enabled=true},
 * like the other reminder schedulers, so turning it on is a deployment decision.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notifications.pharmacy.licence.enabled", havingValue = "true")
public class PharmacyLicenceReminderScheduler {

    private final PharmacyLicenceReminderService reminderService;

    @Scheduled(
            cron = "${app.notifications.pharmacy.licence.cron:0 0 8 * * *}",
            zone = "${app.notifications.pharmacy.licence.zone:Africa/Nairobi}"
    )
    public void licenceReminders() {
        reminderService.sweep();
    }
}
