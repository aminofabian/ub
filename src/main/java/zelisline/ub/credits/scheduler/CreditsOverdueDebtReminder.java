package zelisline.ub.credits.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Placeholder for overdue tab SMS/email (Phase&nbsp;5). Enable with {@code app.credits.reminders.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "app.credits.reminders.enabled", havingValue = "true")
public class CreditsOverdueDebtReminder {

    private static final Logger log = LoggerFactory.getLogger(CreditsOverdueDebtReminder.class);

    @Scheduled(cron = "${app.credits.reminders.cron:0 0 8 * * *}")
    public void sweep() {
        log.info("Credits overdue reminder job ran (integrations stub)");
    }
}
