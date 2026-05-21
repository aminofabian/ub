package zelisline.ub.notifications.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.notifications.application.NotificationDeliveryRetryWorker;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.notifications.delivery.worker.enabled", havingValue = "true")
public class NotificationDeliveryScheduler {

    private final NotificationDeliveryRetryWorker deliveryRetryWorker;

    @Scheduled(
            fixedDelayString = "${app.notifications.delivery.worker.fixed-delay-ms:20000}",
            initialDelayString = "${app.notifications.delivery.worker.initial-delay-ms:10000}"
    )
    public void tick() {
        try {
            deliveryRetryWorker.processDue();
        } catch (RuntimeException ex) {
            log.warn("Notification delivery scheduler tick failed", ex);
        }
    }
}
