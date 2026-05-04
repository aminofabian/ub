package zelisline.ub.reporting.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Audit row for one MV refresh attempt (Phase 7 Slice 1). One run is created per call
 * to {@code ReportingRefreshRunner.run(...)}; failures persist with {@code status='failed'}
 * and a truncated {@code error_message} so the operator runbook can spot lag without
 * reading server logs.
 */
@Getter
@Setter
@Entity
@Table(name = "reporting_refresh_runs")
public class ReportingRefreshRun {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "mv_name", nullable = false, length = 100)
    private String mvName;

    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "rows_changed")
    private Long rowsChanged;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
