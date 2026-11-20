package zelisline.ub.pharmacy.domain;

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
 * A pharmacist approved one sale at a cashier's till by entering their PIN. Short-lived and
 * single-use; the sale request carries only the id.
 */
@Getter
@Setter
@Entity
@Table(name = "dispensing_approvals")
public class DispensingApproval {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "approver_user_id", nullable = false, length = 36)
    private String approverUserId;

    @Column(name = "requested_by", nullable = false, length = 36)
    private String requestedBy;

    /** Comma-separated item ids the pharmacist approved; the sale may not add gated medicines to it. */
    @Column(name = "item_ids", length = 2000)
    private String itemIds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "used_sale_id", length = 36)
    private String usedSaleId;

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
