package zelisline.ub.till.domain;

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
@Table(name = "till_access_requests")
public class TillAccessRequest {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_DISMISSED = "dismissed";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "device_key", nullable = false, length = 64)
    private String deviceKey;

    @Column(name = "requested_by_user_id", nullable = false, length = 36)
    private String requestedByUserId;

    @Column(name = "requested_by_name", nullable = false, length = 160)
    private String requestedByName;

    @Column(name = "requested_by_email", nullable = false, length = 191)
    private String requestedByEmail;

    @Column(name = "suggested_label", nullable = false, length = 80)
    private String suggestedLabel;

    @Column(name = "user_agent", length = 240)
    private String userAgent;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "notified_at")
    private Instant notifiedAt;

    @Column(name = "notify_count", nullable = false)
    private int notifyCount;

    @Column(name = "resolved_by", length = 36)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
        if (status == null || status.isBlank()) {
            status = STATUS_PENDING;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
