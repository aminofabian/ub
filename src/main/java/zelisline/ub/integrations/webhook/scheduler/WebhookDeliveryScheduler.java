package zelisline.ub.integrations.webhook.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.integrations.webhook.application.WebhookDeliveryWorker;

/**
 * Drains webhook outbox deliveries (Phase 8 Slice 2 ADR — transactional enqueue + asynchronous HTTP).
 *
 * <p>Must stay off in most {@link org.springframework.boot.test.context.SpringBootTest}s; enable explicitly
 * in prod or webhook integration specs.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.integrations.webhook.delivery.enabled", havingValue = "true")
public class WebhookDeliveryScheduler {

    private final WebhookDeliveryWorker webhookDeliveryWorker;

    @Scheduled(
            fixedDelayString = "${app.integrations.webhook.delivery.fixed-delay-ms:15000}",
            initialDelayString = "${app.integrations.webhook.delivery.initial-delay-ms:5000}"
    )
    public void tick() {
        try {
            webhookDeliveryWorker.processDue();
        } catch (RuntimeException ex) {
            log.warn("Webhook delivery scheduler tick failed", ex);
        }
    }
}
