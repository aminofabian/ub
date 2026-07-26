package zelisline.ub.marketplace.domain;

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
@Table(name = "marketplace_supplier_product_edit_requests")
public class MarketplaceSupplierProductEditRequest {

    public static final String PENDING = "pending";
    public static final String APPROVED = "approved";
    public static final String REJECTED = "rejected";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "marketplace_supplier_id", nullable = false, length = 36)
    private String marketplaceSupplierId;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(name = "requested_by_user_id", length = 36)
    private String requestedByUserId;

    @Column(name = "status", nullable = false, length = 16)
    private String status = PENDING;

    @Column(name = "proposed_json", nullable = false, columnDefinition = "TEXT")
    private String proposedJson;

    @Column(name = "live_snapshot_json", columnDefinition = "TEXT")
    private String liveSnapshotJson;

    @Column(name = "reviewed_by_user_id", length = 36)
    private String reviewedByUserId;

    @Column(name = "reviewed_business_id", length = 36)
    private String reviewedBusinessId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note", length = 1000)
    private String reviewNote;

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
