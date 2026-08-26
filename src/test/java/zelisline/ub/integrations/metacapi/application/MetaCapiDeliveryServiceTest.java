package zelisline.ub.integrations.metacapi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventPayload;
import zelisline.ub.integrations.metacapi.config.MetaCapiProperties;
import zelisline.ub.integrations.metacapi.domain.MetaCapiEvent;
import zelisline.ub.integrations.metacapi.domain.MetaCapiEventStatuses;
import zelisline.ub.integrations.metacapi.infrastructure.MetaCapiGraphClient;
import zelisline.ub.integrations.metacapi.repository.MetaCapiEventRepository;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.tenancy.application.MetaCapiSettingsService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

class MetaCapiDeliveryServiceTest {

    private static final String CONFIGURED_SETTINGS = """
            {"metaCapi":{"enabled":true,"pixelId":"123456789","accessTokenEnc":"enc-blob","testEventCode":"TEST1"}}
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MetaCapiEventRepository eventRepository = mock(MetaCapiEventRepository.class);
    private final MetaCapiGraphClient graphClient = mock(MetaCapiGraphClient.class);
    private final CredentialEncryptionService credentialEncryptionService = mock(CredentialEncryptionService.class);
    private final BusinessRepository businessRepository = mock(BusinessRepository.class);
    private final MetaCapiProperties properties = mock(MetaCapiProperties.class);
    private final AuditEventPublisher auditEventPublisher = mock(AuditEventPublisher.class);

    private MetaCapiDeliveryService service;

    @BeforeEach
    void setUp() {
        MetaCapiSettingsService settingsService =
                new MetaCapiSettingsService(objectMapper, credentialEncryptionService);
        service = new MetaCapiDeliveryService(
                eventRepository,
                graphClient,
                new MetaCapiPayloadBuilder(objectMapper),
                settingsService,
                credentialEncryptionService,
                businessRepository,
                properties,
                auditEventPublisher,
                new AuditEventBuilder(objectMapper));
        when(properties.retryMaxAttempts()).thenReturn(5);
        when(properties.testEventCode()).thenReturn("");
    }

    private void stubBusiness(String settingsJson) {
        Business business = new Business();
        business.setId("biz-1");
        business.setSettings(settingsJson);
        when(businessRepository.findByIdAndDeletedAtIsNull("biz-1")).thenReturn(Optional.of(business));
    }

    private MetaCapiEnqueueRequest request() {
        return new MetaCapiEnqueueRequest(
                "biz-1",
                "CompleteRegistration",
                "registration_123",
                Instant.now(),
                "https://tenant.example.com/register",
                "website",
                null,
                null);
    }

    // ── enqueue ────────────────────────────────────────────────────────────

    @Test
    void enqueue_unconfiguredTenant_skipsWithoutPersisting() {
        stubBusiness("{}");

        var result = service.enqueue(request());

        assertThat(result.enqueued()).isFalse();
        assertThat(result.skipReason()).contains("not configured");
        verify(eventRepository, never()).save(any());
    }

    @Test
    void enqueue_configured_persistsPendingRow() {
        stubBusiness(CONFIGURED_SETTINGS);

        var result = service.enqueue(request());

        assertThat(result.enqueued()).isTrue();
        ArgumentCaptor<MetaCapiEvent> captor = ArgumentCaptor.forClass(MetaCapiEvent.class);
        verify(eventRepository).save(captor.capture());
        MetaCapiEvent saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(MetaCapiEventStatuses.PENDING);
        assertThat(saved.getPixelId()).isEqualTo("123456789");
        assertThat(saved.getEventId()).isEqualTo("registration_123");
        assertThat(saved.getRequestJson()).contains("\"event_name\":\"CompleteRegistration\"");
        // per-tenant test event code applied at enqueue
        assertThat(saved.getRequestJson()).contains("\"test_event_code\":\"TEST1\"");
    }

    @Test
    void enqueue_duplicateEventId_skips() {
        stubBusiness(CONFIGURED_SETTINGS);
        when(eventRepository.existsByBusinessIdAndEventId("biz-1", "registration_123")).thenReturn(true);

        var result = service.enqueue(request());

        assertThat(result.enqueued()).isFalse();
        assertThat(result.skipReason()).contains("already enqueued");
        verify(eventRepository, never()).save(any());
    }

    @Test
    void enqueue_globalTestEventCodeOverridesTenant() {
        stubBusiness(CONFIGURED_SETTINGS);
        when(properties.testEventCode()).thenReturn("GLOBAL");

        service.enqueue(request());

        ArgumentCaptor<MetaCapiEvent> captor = ArgumentCaptor.forClass(MetaCapiEvent.class);
        verify(eventRepository).save(captor.capture());
        assertThat(captor.getValue().getRequestJson()).contains("\"test_event_code\":\"GLOBAL\"");
        assertThat(captor.getValue().getRequestJson()).doesNotContain("TEST1");
    }

    // ── deliver ────────────────────────────────────────────────────────────

    @Test
    void deliver_success_marksSentAndPublishesAudit() {
        stubBusiness(CONFIGURED_SETTINGS);
        when(credentialEncryptionService.decrypt("enc-blob")).thenReturn("tok-123");
        when(graphClient.send("123456789", "tok-123", "{}"))
                .thenReturn(MetaCapiGraphClient.SendResult.sent("{\"events_received\":1}"));
        MetaCapiEvent event = event("pending", 0);

        service.deliver(event);

        assertThat(event.getStatus()).isEqualTo(MetaCapiEventStatuses.SENT);
        assertThat(event.getSentAt()).isNotNull();
        assertThat(event.getAttemptCount()).isZero();
        assertThat(event.getResponseJson()).contains("events_received");
        verify(eventRepository).save(event);
        ArgumentCaptor<AuditEventPayload> audit = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(auditEventPublisher).publish(audit.capture());
        assertThat(audit.getValue().eventType()).isEqualTo(AuditEventTypes.META_CAPI_DELIVERY_SENT);
        assertThat(audit.getValue().actorType()).isEqualTo(zelisline.ub.audit.domain.AuditEventActorType.SYSTEM);
    }

    @Test
    void deliver_transientFailure_incrementsAttemptsForRetry() {
        stubBusiness(CONFIGURED_SETTINGS);
        when(credentialEncryptionService.decrypt("enc-blob")).thenReturn("tok-123");
        when(graphClient.send("123456789", "tok-123", "{}"))
                .thenReturn(MetaCapiGraphClient.SendResult.failed("http_500: boom", 500, "{}"));
        MetaCapiEvent event = event("pending", 0);

        service.deliver(event);

        assertThat(event.getStatus()).isEqualTo(MetaCapiEventStatuses.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getError()).contains("boom");
        verify(auditEventPublisher).publish(any());
    }

    @Test
    void deliver_authFailure_isTerminal() {
        stubBusiness(CONFIGURED_SETTINGS);
        when(credentialEncryptionService.decrypt("enc-blob")).thenReturn("tok-123");
        when(graphClient.send("123456789", "tok-123", "{}"))
                .thenReturn(MetaCapiGraphClient.SendResult.failed("http_401: bad token", 401, "{}"));
        MetaCapiEvent event = event("pending", 0);

        service.deliver(event);

        assertThat(event.getStatus()).isEqualTo(MetaCapiEventStatuses.FAILED);
        // attempt budget exhausted so the retry sweep never picks it up again
        assertThat(event.getAttemptCount()).isEqualTo(5);
        assertThat(event.getError()).contains("bad token");
    }

    @Test
    void deliver_configNoLongerReady_skipsWithoutSending() {
        stubBusiness("{}");
        MetaCapiEvent event = event("pending", 0);

        service.deliver(event);

        assertThat(event.getStatus()).isEqualTo(MetaCapiEventStatuses.SKIPPED);
        verify(graphClient, never()).send(any(), any(), any());
        verify(auditEventPublisher).publish(any());
    }

    private MetaCapiEvent event(String status, int attempts) {
        MetaCapiEvent event = new MetaCapiEvent();
        event.setId("evt-1");
        event.setBusinessId("biz-1");
        event.setPixelId("123456789");
        event.setEventName("CompleteRegistration");
        event.setEventId("registration_123");
        event.setStatus(status);
        event.setAttemptCount(attempts);
        event.setRequestJson("{}");
        event.setCreatedAt(Instant.now().minusSeconds(60));
        return event;
    }
}
