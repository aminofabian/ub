package zelisline.ub.posdraft.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventPayload;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.posdraft.PosDraftConstants;
import zelisline.ub.posdraft.api.dto.CancelPosDraftRequest;
import zelisline.ub.posdraft.domain.PosDraft;
import zelisline.ub.posdraft.domain.PosDraftLine;
import zelisline.ub.posdraft.infrastructure.BranchPosSequenceAllocator;
import zelisline.ub.posdraft.repository.PosDraftAuditLogRepository;
import zelisline.ub.posdraft.repository.PosDraftLineRepository;
import zelisline.ub.posdraft.repository.PosDraftRepository;
import zelisline.ub.sales.application.OpenShiftResolver;
import zelisline.ub.sales.application.SaleActorNameService;
import zelisline.ub.sales.application.SaleService;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Clearing an unpaid cart at the till must reach the tenant diary (unified
 * audit log) with the shape of what was dropped, not only the per-draft trail.
 */
@ExtendWith(MockitoExtension.class)
class PosDraftServiceClearSaleAuditTest {

    @Mock private PosDraftRepository draftRepository;
    @Mock private PosDraftLineRepository lineRepository;
    @Mock private PosDraftAuditLogRepository auditLogRepository;
    @Mock private BranchPosSequenceAllocator sequenceAllocator;
    @Mock private ItemRepository itemRepository;
    @Mock private BranchRepository branchRepository;
    @Mock private BusinessRepository businessRepository;
    @Mock private SaleActorNameService saleActorNameService;
    @Mock private FeatureFlagService featureFlagService;
    @Mock private SaleService saleService;
    @Mock private OpenShiftResolver openShiftResolver;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AuditEventPublisher auditEventPublisher;
    @Spy private AuditEventBuilder auditEventBuilder = new AuditEventBuilder(new ObjectMapper());

    @InjectMocks
    private PosDraftService service;

    private PosDraft draft;

    @BeforeEach
    void setUp() {
        draft = new PosDraft();
        draft.setId("draft-1");
        draft.setBusinessId("biz-A");
        draft.setBranchId("br-1");
        draft.setTicketNumber(42L);
        draft.setShiftId("shift-9");
        draft.setStatus(PosDraftConstants.STATUS_PENDING);
        draft.setCreatedBy("user-1");
        when(draftRepository.findByIdAndBusinessId("draft-1", "biz-A")).thenReturn(Optional.of(draft));
        when(draftRepository.save(any(PosDraft.class))).thenAnswer(inv -> inv.getArgument(0));
        when(saleActorNameService.resolveSoldByName(anyString(), anyString())).thenReturn("Faith");
    }

    private static PosDraftLine line(int index, String qty, String total) {
        PosDraftLine l = new PosDraftLine();
        l.setId("line-" + index);
        l.setDraftId("draft-1");
        l.setLineIndex(index);
        l.setItemName("Item " + index);
        l.setQuantity(new BigDecimal(qty));
        l.setUnitPrice(BigDecimal.TEN);
        l.setLineTotal(new BigDecimal(total));
        return l;
    }

    @Test
    void clearingACartWithLinesWritesADiaryRowWithShape() {
        when(lineRepository.findByDraftIdOrderByLineIndexAsc("draft-1"))
                .thenReturn(List.of(line(0, "2", "200.00"), line(1, "0.5", "150.00")));

        service.cancelDraft("biz-A", "draft-1", new CancelPosDraftRequest("Cleared from till"), "user-1");

        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(auditEventPublisher).publish(captor.capture());
        AuditEventPayload p = captor.getValue();
        assertThat(p.businessId()).isEqualTo("biz-A");
        assertThat(p.branchId()).isEqualTo("br-1");
        assertThat(p.category()).isEqualTo(AuditEventCategory.SALES);
        assertThat(p.eventType()).isEqualTo(AuditEventTypes.POS_SALE_CLEARED);
        assertThat(p.actorId()).isEqualTo("user-1");
        assertThat(p.actorName()).isEqualTo("Faith");
        assertThat(p.targetType()).isEqualTo("pos_draft");
        assertThat(p.targetLabel()).isEqualTo("Ticket 42");
        assertThat(p.shiftId()).isEqualTo("shift-9");
        assertThat(p.reason()).isEqualTo("Cleared from till");
        assertThat(p.metadata())
                .contains("\"lineCount\":2")
                .contains("\"units\":\"2.5\"")
                .contains("\"value\":\"350.00\"")
                .contains("\"clearAll\":false");
    }

    @Test
    void clearAllUsesItsOwnEventType() {
        when(lineRepository.findByDraftIdOrderByLineIndexAsc("draft-1"))
                .thenReturn(List.of(line(0, "1", "80.00")));

        service.cancelDraft("biz-A", "draft-1", new CancelPosDraftRequest("Cleared all from till", true), "user-1");

        ArgumentCaptor<AuditEventPayload> captor = ArgumentCaptor.forClass(AuditEventPayload.class);
        verify(auditEventPublisher).publish(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(AuditEventTypes.POS_SALES_CLEARED_ALL);
        assertThat(captor.getValue().metadata()).contains("\"clearAll\":true");
    }

    @Test
    void clearingAnEmptyCartIsNotDiaryWorthy() {
        when(lineRepository.findByDraftIdOrderByLineIndexAsc("draft-1")).thenReturn(List.of());

        service.cancelDraft("biz-A", "draft-1", new CancelPosDraftRequest(null), "user-1");

        verify(auditEventPublisher, never()).publish(any());
        assertThat(draft.getStatus()).isEqualTo(PosDraftConstants.STATUS_CANCELLED);
    }
}
