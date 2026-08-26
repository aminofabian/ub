package zelisline.ub.integrations.metacapi.scheduler;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import zelisline.ub.integrations.metacapi.application.MetaCapiDeliveryService;
import zelisline.ub.integrations.metacapi.config.MetaCapiProperties;
import zelisline.ub.integrations.metacapi.domain.MetaCapiEvent;
import zelisline.ub.integrations.metacapi.repository.MetaCapiEventRepository;

/**
 * Drains the Meta CAPI outbox: sends PENDING rows and retries FAILED rows that
 * still have attempt budget. Fixed-delay, single-threaded (no concurrent
 * delivery of the same row).
 */
@Component
public class MetaCapiRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(MetaCapiRetryScheduler.class);

    private static final int BATCH_SIZE = 100;

    private final MetaCapiDeliveryService deliveryService;
    private final MetaCapiEventRepository eventRepository;
    private final MetaCapiProperties properties;

    public MetaCapiRetryScheduler(
            MetaCapiDeliveryService deliveryService,
            MetaCapiEventRepository eventRepository,
            MetaCapiProperties properties) {
        this.deliveryService = deliveryService;
        this.eventRepository = eventRepository;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.meta-capi.retry-interval-ms:30000}")
    public void deliverDueEvents() {
        List<MetaCapiEvent> due =
                eventRepository.findDueForDelivery(properties.retryMaxAttempts(), PageRequest.of(0, BATCH_SIZE));
        for (MetaCapiEvent event : due) {
            try {
                deliveryService.deliver(event);
            } catch (Exception e) {
                log.warn("Meta CAPI delivery failed for event {}: {}",
                        event.getEventId(), e.getMessage());
            }
        }
    }
}
