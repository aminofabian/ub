package zelisline.ub.notifications.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.application.FridayPocketReminderService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.notifications.profit-pocket.friday.enabled", havingValue = "true")
public class FridayPocketReminderScheduler {

    private final FridayPocketReminderService fridayPocketReminderService;

    @Scheduled(
            cron = "${app.notifications.profit-pocket.friday.cron:0 0 18 * * FRI}",
            zone = "${app.notifications.profit-pocket.friday.zone:Africa/Nairobi}"
    )
    public void fridayPocketReminders() {
        fridayPocketReminderService.enqueueFridayReminders();
    }
}
