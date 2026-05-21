package zelisline.ub.notifications.application;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.notifications.domain.NotificationDelivery;
import zelisline.ub.notifications.repository.NotificationDeliveryRepository;

/**
 * Retries non-IN_APP deliveries (Phase B: marks unimplemented channels SKIPPED at creation;
 * this worker is ready for Phase C SMS/push).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryRetryWorker {

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationDeliveryTxnService deliveryTxnService;

    public void processDue() {
        Instant now = Instant.now();
        List<NotificationDelivery> batch =
                deliveryRepository.findDuePending(now, PageRequest.of(0, 50));
        for (NotificationDelivery delivery : batch) {
            try {
                attemptOne(delivery.getId());
            } catch (RuntimeException ex) {
                log.warn("notification delivery retry threw deliveryId={}", delivery.getId(), ex);
            }
        }
    }

    void attemptOne(String deliveryId) {
        deliveryTxnService.attemptDelivery(deliveryId);
    }
}
