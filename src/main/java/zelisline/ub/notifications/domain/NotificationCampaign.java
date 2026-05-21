package zelisline.ub.notifications.domain;

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
@Table(name = "notification_campaigns")
public class NotificationCampaign {

    public static final String TYPE_FLASH_SALE = "FLASH_SALE";
    public static final String TYPE_WEEKLY_DEALS = "WEEKLY_DEALS";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SCHEDULED = "SCHEDULED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final String SCOPE_ALL_BUYERS = "ALL_BUYERS";
    public static final String SCOPE_ACTIVE_BUYERS_90D = "ACTIVE_BUYERS_90D";
    public static final String SCOPE_INACTIVE_BUYERS_30D = "INACTIVE_BUYERS_30D";
    public static final String SCOPE_BRANCH_ACTIVE_BUYERS_90D = "BRANCH_ACTIVE_BUYERS_90D";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "campaign_type", nullable = false, length = 32)
    private String campaignType;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "body", nullable = false, length = 2000)
    private String body;

    @Column(name = "action_url", length = 512)
    private String actionUrl;

    @Column(name = "recipient_scope", nullable = false, length = 32)
    private String recipientScope;

    @Column(name = "catalog_branch_id", length = 36)
    private String catalogBranchId;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "recipients_targeted", nullable = false)
    private int recipientsTargeted;

    @Column(name = "recipients_sent", nullable = false)
    private int recipientsSent;

    @Column(name = "created_by_user_id", length = 36)
    private String createdByUserId;

    @Column(name = "created_at", nullable = false)
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
        if (status == null || status.isBlank()) {
            status = STATUS_DRAFT;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
