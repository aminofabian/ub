package zelisline.ub.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
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

@Getter
@Setter
@Entity
@Table(
        name = "sales",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_sales_business_idem",
                columnNames = {"business_id", "idempotency_key"}
        )
)
public class Sale {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "shift_id", nullable = false, length = 36)
    private String shiftId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "idempotency_key", nullable = false, length = 191)
    private String idempotencyKey;

    @Column(name = "grand_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "journal_entry_id", length = 36)
    private String journalEntryId;

    @Column(name = "sold_by", nullable = false, length = 36)
    private String soldBy;

    @Column(name = "sold_at", nullable = false)
    private Instant soldAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by", length = 36)
    private String voidedBy;

    @Column(name = "void_journal_entry_id", length = 36)
    private String voidJournalEntryId;

    @Column(name = "void_notes", length = 2000)
    private String voidNotes;

    @Column(name = "refunded_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal refundedTotal = BigDecimal.ZERO;

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
        if (soldAt == null) {
            soldAt = now;
        }
        if (refundedTotal == null) {
            refundedTotal = BigDecimal.ZERO;
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
