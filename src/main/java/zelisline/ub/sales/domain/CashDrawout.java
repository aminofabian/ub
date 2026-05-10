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
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "cash_drawouts")
public class CashDrawout {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "shift_id", nullable = false, length = 36)
    private String shiftId;

    @Column(name = "register_id", length = 36)
    private String registerId;

    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Column(name = "recurring_item_id", length = 36)
    private String recurringItemId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "description", nullable = false, length = 300)
    private String description;

    @Column(name = "recipient_name", nullable = false, length = 255)
    private String recipientName;

    @Column(name = "recipient_contact", length = 100)
    private String recipientContact;

    @Column(name = "reference", length = 255)
    private String reference;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "approval_tier", nullable = false)
    private int approvalTier;

    @Column(name = "initiated_by", nullable = false, length = 36)
    private String initiatedBy;

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejected_by", length = 36)
    private String rejectedBy;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "voided_by", length = 36)
    private String voidedBy;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

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
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
