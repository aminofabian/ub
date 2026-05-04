package zelisline.ub.integrations.webhook.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.webhook.WebhookEventTypes;
import zelisline.ub.integrations.webhook.domain.WebhookDelivery;
import zelisline.ub.integrations.webhook.domain.WebhookSubscription;
import zelisline.ub.integrations.webhook.repository.WebhookDeliveryRepository;
import zelisline.ub.integrations.webhook.repository.WebhookSubscriptionRepository;

/**
 * Writes {@link WebhookDelivery} rows in the OLTP transaction (transactional-outbox-lite).
 *
 * <p>{@link WebhookDeliveryWorker} posts signed HTTP requests asynchronously via a scheduler —
 * integrations traffic never blocks the POS completion path unless this insert fails.</p>
 */
@Service
@RequiredArgsConstructor
public class WebhookEnqueueService {

    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;

    /** @param idempotencyKey dedupes deliveries per ({@code subscription_id}, {@code key}); omit for fire-and-repeat. */
    @Transactional
    public void enqueue(
            String businessId,
            String eventType,
            Map<String, Object> dataPayload,
            String idempotencyKeyOrNull
    ) {
        if (!WebhookEventTypes.isKnown(eventType)) {
            return;
        }
        List<WebhookSubscription> subs =
                subscriptionRepository.findByBusinessIdAndActiveIsTrueOrderByCreatedAtAsc(businessId);
        if (subs.isEmpty()) {
            return;
        }
        for (WebhookSubscription sub : subs) {
            List<String> ev = sub.getEvents();
            if (ev == null || !ev.contains(eventType)) {
                continue;
            }
            if (idempotencyKeyOrNull != null
                    && deliveryRepository.existsBySubscriptionIdAndIdempotencyKey(sub.getId(), idempotencyKeyOrNull)) {
                continue;
            }
            String body;
            try {
                body = buildEnvelopeJson(eventType, dataPayload);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("webhook serialization failed", e);
            }
            WebhookDelivery delivery = new WebhookDelivery();
            delivery.setBusinessId(businessId);
            delivery.setSubscriptionId(sub.getId());
            delivery.setEventType(eventType);
            delivery.setPayloadJson(body);
            delivery.setStatus(WebhookDelivery.STATUS_PENDING);
            delivery.setIdempotencyKey(idempotencyKeyOrNull);
            delivery.setAttemptCount(0);
            delivery.setNextAttemptAt(null);
            try {
                deliveryRepository.save(delivery);
            } catch (DataIntegrityViolationException ex) {
                // concurrent duplicate enqueue for same subscription+idempotency
            }
        }
    }

    private String buildEnvelopeJson(String eventType, Map<String, Object> dataPayload)
            throws JsonProcessingException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event", eventType);
        envelope.put("sentAt", Instant.now().toString());
        envelope.put("data", dataPayload == null ? Map.of() : dataPayload);
        return objectMapper.writeValueAsString(envelope);
    }
}
