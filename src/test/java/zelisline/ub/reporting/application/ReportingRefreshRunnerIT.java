package zelisline.ub.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import zelisline.ub.reporting.domain.ReportingRefreshRun;
import zelisline.ub.reporting.repository.ReportingRefreshRunRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Slice 1 — verifies the refresh runner persists a {@code reporting_refresh_runs} row
 * for every attempt regardless of outcome. The lag metric and "block export if lag &gt; N
 * hours" risk mitigation in PHASE_7_PLAN.md depend on this row existing.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ReportingRefreshRunnerIT {

    private static final String BUSINESS = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb6";

    @Autowired
    private ReportingRefreshRunner runner;
    @Autowired
    private ReportingRefreshRunRepository runRepository;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @BeforeEach
    void resetRuns() {
        runRepository.deleteAll();
    }

    @Test
    void run_success_persistsAuditRowWithRowsChangedAndDuration() {
        ReportingMvRefresher refresher = new StubRefresher("mv_test_success", 42L);

        ReportingRefreshRun result = runner.run(refresher, BUSINESS);

        assertThat(result.getStatus()).isEqualTo("success");
        assertThat(result.getRowsChanged()).isEqualTo(42L);
        assertThat(result.getFinishedAt()).isNotNull();
        assertThat(result.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(result.getErrorMessage()).isNull();

        ReportingRefreshRun fetched = runRepository.findById(result.getId()).orElseThrow();
        assertThat(fetched.getMvName()).isEqualTo("mv_test_success");
        assertThat(fetched.getBusinessId()).isEqualTo(BUSINESS);
    }

    @Test
    void run_failure_persistsRowWithStatusFailedAndPropagates() {
        ReportingMvRefresher refresher = new ThrowingRefresher("mv_test_failure", "boom");

        assertThatThrownBy(() -> runner.run(refresher, BUSINESS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        ReportingRefreshRun row = runRepository
                .findFirstByMvNameOrderByStartedAtDesc("mv_test_failure")
                .orElseThrow();
        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getErrorMessage()).contains("IllegalStateException").contains("boom");
        assertThat(row.getRowsChanged()).isNull();
        assertThat(row.getFinishedAt()).isNotNull();
    }

    private record StubRefresher(String name, long rows) implements ReportingMvRefresher {
        @Override public String mvName() { return name; }
        @Override public long refresh(String businessId) { return rows; }
    }

    private record ThrowingRefresher(String name, String message) implements ReportingMvRefresher {
        @Override public String mvName() { return name; }
        @Override public long refresh(String businessId) { throw new IllegalStateException(message); }
    }
}
