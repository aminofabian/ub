package zelisline.ub.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/** One nightly restock digest generation for a branch on a business-local day. */
@Getter
@Setter
@Entity
@Table(
        name = "restock_runs",
        uniqueConstraints =
                @UniqueConstraint(name = "uq_restock_run_branch_date", columnNames = {"branch_id", "run_date"}))
public class RestockRun {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    /** generated | notified | partially_accepted | accepted | expired */
    @Column(name = "status", nullable = false, length = 32)
    private String status = "generated";

    @Column(name = "line_count", nullable = false)
    private int lineCount;

    @Column(name = "po_line_count", nullable = false)
    private int poLineCount;

    @Column(name = "pad_line_count", nullable = false)
    private int padLineCount;

    @Column(name = "est_total", nullable = false, precision = 14, scale = 4)
    private BigDecimal estTotal = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "KES";

    /** scheduled | manual — column is backtick-quoted; TRIGGER is reserved in MySQL. */
    @Column(name = "`trigger`", nullable = false, length = 16)
    private String trigger = "scheduled";

    @Column(name = "error_note", columnDefinition = "TEXT")
    private String errorNote;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
