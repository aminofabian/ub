package zelisline.ub.integrations.metacapi.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.integrations.metacapi.config.MetaCapiProperties;
import zelisline.ub.integrations.metacapi.domain.MetaCapiEvent;
import zelisline.ub.integrations.metacapi.domain.MetaCapiEventStatuses;
import zelisline.ub.integrations.metacapi.infrastructure.MetaCapiGraphClient;
import zelisline.ub.integrations.metacapi.repository.MetaCapiEventRepository;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.tenancy.application.MetaCapiSettingsService;
import zelisline.ub.tenancy.application.MetaCapiSettingsService.MetaCapiRuntimeConfig;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Durable Meta Conversions API pipeline: {@link #enqueue} persists a PENDING
 * row with the assembled request body; {@link #deliver} (driven by
 * {@code MetaCapiRetryScheduler}) decrypts the tenant token fresh, sends to
 * Graph, and records the outcome on the row — which doubles as the restricted
 * super-admin delivery audit.
 *
 * <p>No-op for tenants that haven't configured Meta: enqueue returns skipped and
 * nothing is persisted. The access token is never stored on the row or logged.
 */
@Service
@RequiredArgsConstructor
public class MetaCapiDeliveryService {

    private static final Logger log = LoggerFactory.getLogger(MetaCapiDeliveryService.class);

    private static final int MAX_STORED_RESPONSE = 8000;
    private static final int MAX_STORED_ERROR = 1000;

    private final MetaCapiEventRepository eventRepository;
    private final MetaCapiGraphClient graphClient;
    private final MetaCapiPayloadBuilder payloadBuilder;
    private final MetaCapiSettingsService metaCapiSettingsService;
    private final CredentialEncryptionService credentialEncryptionService;
    private final BusinessRepository businessRepository;
    private final MetaCapiProperties properties;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    public record EnqueueResult(boolean enqueued, String eventId, String skipReason) {

        public static EnqueueResult enqueued(String eventId) {
            return new EnqueueResult(true, eventId, null);
        }

        public static EnqueueResult skipped(String eventId, String reason) {
            return new EnqueueResult(false, eventId, reason);
        }
    }

    /**
     * Persist a durable CAPI event for later delivery. Idempotent per
     * {@code (businessId, eventId)}; skips silently when the tenant has no
     * valid Meta config. Call from within the signup / deposit-confirmation
     * transaction — the PENDING row commits atomically with the business change.
     */
    @Transactional
    public EnqueueResult enqueue(MetaCapiEnqueueRequest request) {
        MetaCapiRuntimeConfig config = runtimeConfig(request.businessId());
        if (!config.ready()) {
            return EnqueueResult.skipped(request.eventId(), "tenant metaCapi not configured");
        }
        if (eventRepository.existsByBusinessIdAndEventId(request.businessId(), request.eventId())) {
            return EnqueueResult.skipped(request.eventId(), "event already enqueued");
        }
        MetaCapiEvent row = new MetaCapiEvent();
        row.setBusinessId(request.businessId());
        row.setPixelId(config.pixelId());
        row.setEventName(request.eventName());
        row.setEventId(request.eventId());
        row.setStatus(MetaCapiEventStatuses.PENDING);
        row.setRequestJson(payloadBuilder.build(request, effectiveTestEventCode(config)));
        eventRepository.save(row);
        log.info("Meta CAPI event enqueued: business={} event={} id={}",
                request.businessId(), request.eventName(), request.eventId());
        return EnqueueResult.enqueued(request.eventId());
    }

    /**
     * Send one event and record the outcome. Terminal failures (auth, bad
     * payload) exhaust the attempt budget so the retry sweep skips them.
     */
    @Transactional
    public void deliver(MetaCapiEvent event) {
        MetaCapiRuntimeConfig config = runtimeConfig(event.getBusinessId());
        if (!config.ready() || !config.hasToken()) {
            markSkipped(event, "tenant metaCapi no longer configured");
            return;
        }
        String token = credentialEncryptionService.decrypt(config.accessTokenEnc());
        if (token == null || token.isBlank()) {
            markSkipped(event, "access token unavailable");
            return;
        }
        MetaCapiGraphClient.SendResult result =
                graphClient.send(event.getPixelId(), token, event.getRequestJson());
        if (result.sent()) {
            event.setStatus(MetaCapiEventStatuses.SENT);
            event.setSentAt(Instant.now());
            event.setHttpStatus(result.httpStatus());
            event.setResponseJson(truncate(result.responseBody(), MAX_STORED_RESPONSE));
            event.setError(null);
            eventRepository.save(event);
            publishAudit(event, AuditEventTypes.META_CAPI_DELIVERY_SENT, AuditEventSeverity.INFO);
            log.info("Meta CAPI event sent: business={} event={} id={}",
                    event.getBusinessId(), event.getEventName(), event.getEventId());
        } else {
            event.setStatus(MetaCapiEventStatuses.FAILED);
            event.setAttemptCount(event.getAttemptCount() + 1);
            event.setHttpStatus(result.httpStatus());
            event.setResponseJson(truncate(result.responseBody(), MAX_STORED_RESPONSE));
            event.setError(truncate(result.detail(), MAX_STORED_ERROR));
            if (!result.retryable()) {
                event.setAttemptCount(properties.retryMaxAttempts());
            }
            eventRepository.save(event);
            publishAudit(event, AuditEventTypes.META_CAPI_DELIVERY_FAILED, AuditEventSeverity.WARN);
            log.warn("Meta CAPI delivery failed: business={} event={} attempts={} detail={}",
                    event.getBusinessId(), event.getEventId(), event.getAttemptCount(),
                    truncate(result.detail(), 400));
        }
    }

    private void markSkipped(MetaCapiEvent event, String reason) {
        event.setStatus(MetaCapiEventStatuses.SKIPPED);
        event.setError(truncate(reason, MAX_STORED_ERROR));
        eventRepository.save(event);
        publishAudit(event, AuditEventTypes.META_CAPI_DELIVERY_SKIPPED, AuditEventSeverity.INFO);
        log.info("Meta CAPI event skipped: business={} event={} reason={}",
                event.getBusinessId(), event.getEventId(), reason);
    }

    private MetaCapiRuntimeConfig runtimeConfig(String businessId) {
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .map(business -> metaCapiSettingsService.readRuntimeConfig(business.getSettings()))
                .orElseGet(MetaCapiRuntimeConfig::disabled);
    }

    private String effectiveTestEventCode(MetaCapiRuntimeConfig config) {
        String global = properties.testEventCode();
        if (global != null && !global.isBlank()) {
            return global.trim();
        }
        return config.testEventCode();
    }

    /**
     * Privacy-safe tenant-visible trail: identifiers and timestamps only. The
     * full request/response lives on the outbox row (super-admin delivery log).
     */
    private void publishAudit(MetaCapiEvent event, String eventType, AuditEventSeverity severity) {
        try {
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("eventName", event.getEventName());
            metadata.put("eventId", event.getEventId());
            metadata.put("status", event.getStatus());
            metadata.put("httpStatus", event.getHttpStatus());
            metadata.put("attemptCount", event.getAttemptCount());
            metadata.put("ageSeconds", ageSeconds(event));
            auditEventPublisher.publish(auditEventBuilder.builder(AuditEventCategory.SYSTEM, eventType, severity)
                    .businessId(event.getBusinessId())
                    .actor(null, AuditEventActorType.SYSTEM)
                    .target("meta_capi_event", event.getEventId())
                    .targetLabel(event.getEventName())
                    .source("meta_capi")
                    .metadata(metadata)
                    .build());
        } catch (Exception e) {
            log.warn("Failed to publish Meta CAPI audit event: {}", e.getMessage());
        }
    }

    private static long ageSeconds(MetaCapiEvent event) {
        if (event.getCreatedAt() == null) {
            return 0;
        }
        Instant base = event.getSentAt() != null ? event.getSentAt() : Instant.now();
        return Math.max(0, ChronoUnit.SECONDS.between(event.getCreatedAt(), base));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
