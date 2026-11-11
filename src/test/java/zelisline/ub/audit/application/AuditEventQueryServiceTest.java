package zelisline.ub.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import zelisline.ub.audit.api.dto.AuditEventSummaryResponse;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.audit.repository.AuditEventRepository;

/**
 * The diary's trust boundary is the {@code businessId} the controller resolves
 * from the request host — never anything the client sends. These tests pin that
 * every repository call carries it, plus the multi-type filter and summary shape.
 */
@ExtendWith(MockitoExtension.class)
class AuditEventQueryServiceTest {

    @Mock
    private AuditEventRepository repository;

    @Test
    void eventTypeListPrefersExactTypeThenSplitsAndDedupes() {
        assertThat(AuditEventQueryService.eventTypeList("sale.voided", "a,b")).containsExactly("sale.voided");
        assertThat(AuditEventQueryService.eventTypeList(null, " sale.voided, login.failed ,sale.voided,, "))
                .containsExactly("sale.voided", "login.failed");
        assertThat(AuditEventQueryService.eventTypeList(null, null)).isEmpty();
        assertThat(AuditEventQueryService.eventTypeList("  ", "  ")).isEmpty();
    }

    @Test
    void searchIsScopedToCallerBusinessAndPassesTypeSet() {
        AuditEventQueryService service = new AuditEventQueryService(repository);
        when(repository.search(any(), any(), any(), anyBoolean(), anyList(), anyList(),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty());

        service.search("biz-A", null, null, null, "sale.voided,pos_sale.cleared",
                null, AuditEventSeverity.WARN, null, null, null, null, null, null,
                PageRequest.of(0, 20));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> types = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditEventSeverity>> severities = ArgumentCaptor.forClass(List.class);
        verify(repository).search(eq("biz-A"), isNull(), isNull(), eq(false), types.capture(),
                severities.capture(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any());
        assertThat(types.getValue()).containsExactly("sale.voided", "pos_sale.cleared");
        assertThat(severities.getValue())
                .containsExactly(AuditEventSeverity.WARN, AuditEventSeverity.ERROR, AuditEventSeverity.CRITICAL);
    }

    @Test
    void searchWithoutTypesUsesAnyTypePlaceholder() {
        AuditEventQueryService service = new AuditEventQueryService(repository);
        when(repository.search(any(), any(), any(), anyBoolean(), anyList(), anyList(),
                any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty());

        service.search("biz-B", "br-1", null, null, null, null, null, null, null, null, null,
                null, null, PageRequest.of(0, 20));

        verify(repository).search(eq("biz-B"), eq("br-1"), isNull(), eq(true), eq(List.of("*")),
                anyList(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    void summarizeRollsUpSeverityCategoryAndEventType() {
        AuditEventQueryService service = new AuditEventQueryService(repository);
        Instant from = Instant.parse("2026-11-11T00:00:00Z");
        when(repository.countBySeverityCategoryAndType(eq("biz-A"), isNull(), isNull(), eq(true),
                eq(List.of("*")), anyList(), eq(from), isNull()))
                .thenReturn(List.of(
                        new Object[] {AuditEventSeverity.INFO, AuditEventCategory.SALES, "sale.voided", 3L},
                        new Object[] {AuditEventSeverity.WARN, AuditEventCategory.SECURITY, "login.failed", 5L},
                        new Object[] {AuditEventSeverity.INFO, AuditEventCategory.SALES, "pos_sale.cleared", 2L}
                ));

        AuditEventSummaryResponse summary = service.summarize("biz-A", null, null, null, null,
                null, null, from, null);

        assertThat(summary.total()).isEqualTo(10);
        assertThat(summary.bySeverity()).containsEntry(AuditEventSeverity.INFO, 5L)
                .containsEntry(AuditEventSeverity.WARN, 5L);
        assertThat(summary.byCategory()).containsEntry(AuditEventCategory.SALES, 5L)
                .containsEntry(AuditEventCategory.SECURITY, 5L);
        assertThat(summary.byEventType()).containsEntry("sale.voided", 3L)
                .containsEntry("login.failed", 5L)
                .containsEntry("pos_sale.cleared", 2L);
    }
}
