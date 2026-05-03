package zelisline.ub.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(
        name = "refunds",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_refunds_business_idem",
                columnNames = {"business_id", "idempotency_key"}
        )
)
public class Refund {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "sale_id", nullable = false, length = 36)
    private String saleId;

    @Column(name = "idempotency_key", nullable = false, length = 191)
    private String idempotencyKey;

    @Column(name = "journal_entry_id", length = 36)
    private String journalEntryId;

    @Column(name = "refunded_by", nullable = false, length = 36)
    private String refundedBy;

    @Column(name = "refunded_at", nullable = false)
    private Instant refundedAt;

    @Column(name = "total_refunded", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalRefunded;

    @Column(name = "reason", length = 512)
    private String reason;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (refundedAt == null) {
            refundedAt = Instant.now();
        }
    }
}
