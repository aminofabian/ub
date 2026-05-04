package zelisline.ub.integrations.webhook.application;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.integrations.webhook.domain.WebhookDelivery;
import zelisline.ub.integrations.webhook.repository.WebhookDeliveryRepository;

/**
 * Pulls due {@link WebhookDelivery} rows and hands them
 * to {@link WebhookDeliveryTxnService} (short per-row transactions + HTTP).
 *
 * <p>Phase 8 Slice 2 — single-node friendly; clustered deployments should add claim semantics
 * ({@code SKIP LOCKED}) later.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebhookDeliveryWorker {

    private static final int BATCH = 50;

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookDeliveryTxnService txnService;

    public void processDue() {
        Instant now = Instant.now();
        List<WebhookDelivery> batch =
                deliveryRepository.findDuePending(now, PageRequest.of(0, BATCH));
        if (batch.isEmpty()) {
            return;
        }
        for (var d : batch) {
            try {
                txnService.attemptDelivery(d.getId());
            } catch (RuntimeException ex) {
                log.warn("webhook delivery threw deliveryId={}", d.getId(), ex);
            }
        }
    }
}
