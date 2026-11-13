package zelisline.ub.posdraft.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.posdraft.application.ServiceReminderService;

/**
 * Sends booking reminders every few minutes. On by default; set
 * {@code app.services.reminders.enabled=false} to switch it off (a till running on its own has
 * no SMS provider and skips every shop anyway).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.services.reminders.enabled", havingValue = "true", matchIfMissing = true)
public class ServiceAppointmentReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ServiceAppointmentReminderScheduler.class);

    private final ServiceReminderService reminderService;

    @Scheduled(
            fixedDelayString = "${app.services.reminders.sweep-ms:300000}",
            initialDelayString = "${app.services.reminders.sweep-initial-ms:180000}")
    public void sweep() {
        try {
            int sent = reminderService.sendDue();
            if (sent > 0) {
                log.info("Booking reminders: {} sent", sent);
            }
        } catch (RuntimeException e) {
            log.warn("Booking reminder sweep failed: {}", e.getMessage());
        }
    }
}
