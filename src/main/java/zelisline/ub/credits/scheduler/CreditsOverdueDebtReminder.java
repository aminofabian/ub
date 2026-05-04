package zelisline.ub.credits.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.OverdueDebtReminderService;

/**
 * Daily sweep that delegates to {@link OverdueDebtReminderService}. Enabled by setting
 * {@code app.credits.reminders.enabled=true}; the cron defaults to 08:00 in the JVM
 * default zone but should be set per-deployment via {@code app.credits.reminders.cron}.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.credits.reminders.enabled", havingValue = "true")
public class CreditsOverdueDebtReminder {

    private final OverdueDebtReminderService overdueDebtReminderService;

    @Scheduled(cron = "${app.credits.reminders.cron:0 0 8 * * *}")
    public void sweep() {
        overdueDebtReminderService.sweep();
    }
}
