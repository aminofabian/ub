package zelisline.ub.tenancy.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventPayload;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.tenancy.repository.BranchRepository;

class CashierCapabilityAuditServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StorefrontSettingsService settings =
            new StorefrontSettingsService(objectMapper, mock(BranchRepository.class));
    private final AuditEventPublisher publisher = mock(AuditEventPublisher.class);
    private final CashierCapabilityAuditService service =
            new CashierCapabilityAuditService(settings, publisher, new AuditEventBuilder(objectMapper));

    private static String flags(String json) {
        return "{\"featureFlags\":" + json + "}";
    }

    @Test
    void turningClearSaleOnIsAWarnRowWithBeforeAndAfter() {
        service.publishChanges("biz-A", "user-1",
                flags("{}"),
                flags("{\"pos.cashier_clear_sale\":true}"));

        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(publisher).publish(captor.capture());
        AuditEventPayload p = captor.getValue();
        assertThat(p.businessId()).isEqualTo("biz-A");
        assertThat(p.eventType()).isEqualTo(AuditEventTypes.SETTINGS_CASHIER_CAPABILITY_CHANGED);
        assertThat(p.category()).isEqualTo(AuditEventCategory.SECURITY);
        assertThat(p.severity()).isEqualTo(AuditEventSeverity.WARN);
        assertThat(p.actorId()).isEqualTo("user-1");
        assertThat(p.actorType()).isEqualTo(AuditEventActorType.USER);
        assertThat(p.targetId()).isEqualTo("pos.cashier_clear_sale");
        assertThat(p.oldState()).contains("\"enabled\":false");
        assertThat(p.newState()).contains("\"enabled\":true");
        assertThat(p.metadata()).contains("\"loosened\":true");
    }

    @Test
    void turningACapabilityOffIsInfo() {
        service.publishChanges("biz-A", "user-1",
                flags("{\"pos.cashier_price_edit\":true}"),
                flags("{\"pos.cashier_price_edit\":false}"));

        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().severity()).isEqualTo(AuditEventSeverity.INFO);
        assertThat(captor.getValue().metadata()).contains("\"loosened\":false");
    }

    @Test
    void unchangedOrUnwatchedFlagsPublishNothing() {
        service.publishChanges("biz-A", "user-1",
                flags("{\"pos.cashier_clear_sale\":true,\"ui.opening_loader\":false}"),
                flags("{\"pos.cashier_clear_sale\":true,\"ui.opening_loader\":true}"));

        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void absentActorIsRecordedAsSystem() {
        service.publishChanges("biz-A", null, null, flags("{\"pos.cashier_drawout\":true}"));

        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().actorType()).isEqualTo(AuditEventActorType.SYSTEM);
    }
}
