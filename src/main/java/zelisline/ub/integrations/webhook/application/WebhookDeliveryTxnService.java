package zelisline.ub.integrations.webhook.application;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import kong.unirest.HttpResponse;
import kong.unirest.Unirest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.integrations.webhook.domain.WebhookDelivery;
import zelisline.ub.integrations.webhook.domain.WebhookSubscription;
import zelisline.ub.integrations.webhook.repository.WebhookDeliveryRepository;
import zelisline.ub.integrations.webhook.repository.WebhookSubscriptionRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookDeliveryTxnService {

    private final WebhookDeliveryRepository deliveryRepository;
    private final WebhookSubscriptionRepository subscriptionRepository;
    private final WebhookSigner webhookSigner;

    @Value("${app.integrations.webhook.delivery.http-timeout-seconds:15}")
    private int httpTimeoutSeconds;

    @Value("${app.integrations.webhook.delivery.max-attempts:12}")
    private int maxAttempts;

    @Value("${app.integrations.webhook.subscription.failure-disable-threshold:10}")
    private int subscriptionDisableThreshold;

    /**
     * One POST attempt in its own transaction — isolates failures across the batch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attemptDelivery(String deliveryId) {
        WebhookDelivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null || !WebhookDelivery.STATUS_PENDING.equals(delivery.getStatus())) {
            return;
        }
        WebhookSubscription sub = subscriptionRepository.findById(delivery.getSubscriptionId()).orElse(null);
        if (sub == null || !sub.isActive()) {
            markDead(delivery, null, "subscription missing or inactive");
            return;
        }
        if (!sub.getTargetUrl().startsWith("http://") && !sub.getTargetUrl().startsWith("https://")) {
            markDead(delivery, null, "invalid target_url scheme");
            bumpSubscriptionFailure(sub);
            return;
        }

        long t = Instant.now().getEpochSecond();
        String body = delivery.getPayloadJson();
        String sig = webhookSigner.sign(t, body, sub.getSigningSecret());
        String sigHeader = "t=" + t + ",v1=" + sig;

        HttpResponse<String> response;
        try {
            response = Unirest.post(sub.getTargetUrl())
                    .connectTimeout(httpTimeoutSeconds * 1000)
                    .socketTimeout(httpTimeoutSeconds * 1000)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header(WebhookSigner.HEADER_SIGNATURE, sigHeader)
                    .header("Idempotency-Key", delivery.getId())
                    .body(body)
                    .asString();
        } catch (Exception ex) {
            log.debug("webhook transport error deliveryId={} url={}", deliveryId, sub.getTargetUrl(), ex);
            onDeliveryFailureMaybeDead(delivery, sub, null, truncate(ex.getMessage()));
            return;
        }

        int code = response.getStatus();
        if (code >= HttpStatus.OK.value() && code < HttpStatus.MULTIPLE_CHOICES.value()) {
            delivery.setStatus(WebhookDelivery.STATUS_SENT);
            delivery.setSentAt(Instant.now());
            delivery.setLastHttpStatus(code);
            delivery.setLastError(null);
            deliveryRepository.save(delivery);
            sub.setFailureCount(0);
            subscriptionRepository.save(sub);
            return;
        }
        onDeliveryFailureMaybeDead(delivery, sub, code, truncate(response.getBody()));
    }

    /** Retries or marks dead; increments subscription failures only once the delivery reaches {@code DEAD}. */
    private void onDeliveryFailureMaybeDead(WebhookDelivery delivery, WebhookSubscription sub, Integer httpStatus, String err) {
        int nextAttempt = delivery.getAttemptCount() + 1;
        delivery.setAttemptCount(nextAttempt);
        delivery.setLastHttpStatus(httpStatus);
        delivery.setLastError(err);

        if (nextAttempt >= maxAttempts) {
            delivery.setStatus(WebhookDelivery.STATUS_DEAD);
            delivery.setNextAttemptAt(null);
            deliveryRepository.save(delivery);
            bumpSubscriptionFailure(sub);
            return;
        }
        long delaySec = backoffSeconds(nextAttempt);
        delivery.setNextAttemptAt(Instant.now().plusSeconds(delaySec));
        deliveryRepository.save(delivery);
    }

    private void markDead(WebhookDelivery delivery, Integer httpStatus, String err) {
        delivery.setStatus(WebhookDelivery.STATUS_DEAD);
        delivery.setLastHttpStatus(httpStatus);
        delivery.setLastError(err);
        delivery.setNextAttemptAt(null);
        deliveryRepository.save(delivery);
    }

    private void bumpSubscriptionFailure(WebhookSubscription sub) {
        int fc = sub.getFailureCount() + 1;
        sub.setFailureCount(fc);
        if (fc >= subscriptionDisableThreshold) {
            sub.setActive(false);
            log.warn("webhook subscription auto-disabled after repeated dead deliveries id={}", sub.getId());
        }
        subscriptionRepository.save(sub);
    }

    /** Capped exponential backoff with small jitter to desynchronise tenants. */
    private static long backoffSeconds(int attemptAfterIncrement) {
        int exp = Math.min(10, Math.max(1, attemptAfterIncrement));
        long base = Math.min(3600L, (1L << exp) * 15L);
        int jitter = ThreadLocalRandom.current().nextInt(0, 30);
        return base + jitter;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }
}
