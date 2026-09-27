package zelisline.ub.purchasing.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A purchase-order or goods-receipt slip waiting for one cashier till to print. */
@Getter
@Setter
@Entity
@Table(
        name = "till_print_jobs",
        indexes = @Index(
                name = "idx_till_print_pending",
                columnList = "business_id, target_user_id, claimed_at, created_at"))
public class TillPrintJob {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Column(name = "target_user_id", nullable = false, length = 36)
    private String targetUserId;

    /** {@code order} or {@code receipt}. */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "reference_no", nullable = false, length = 80)
    private String referenceNo;

    @Column(name = "payload_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String payloadJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

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
