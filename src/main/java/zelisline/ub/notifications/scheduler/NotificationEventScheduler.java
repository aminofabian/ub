package zelisline.ub.notifications.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.notifications.application.NotificationEventWorker;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.notifications.worker.enabled", havingValue = "true")
public class NotificationEventScheduler {

    private final NotificationEventWorker eventWorker;

    @Scheduled(
            fixedDelayString = "${app.notifications.worker.fixed-delay-ms:5000}",
            initialDelayString = "${app.notifications.worker.initial-delay-ms:8000}"
    )
    public void tick() {
        try {
            eventWorker.processDue();
        } catch (RuntimeException ex) {
            log.warn("Notification event scheduler tick failed", ex);
        }
    }
}
