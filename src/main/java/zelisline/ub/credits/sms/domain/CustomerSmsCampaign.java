package zelisline.ub.credits.sms.domain;

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

/**
 * A promotional SMS to a customer audience (SMS Hub, ADR 0013). SMS twin of
 * {@code CustomerEmailCampaign}, plus scheduling and background sending.
 *
 * <p>Lifecycle: DRAFT → QUEUED (send now) or SCHEDULED → RUNNING → COMPLETED / FAILED;
 * DRAFT, QUEUED and SCHEDULED can be CANCELLED. A RUNNING campaign that reaches the
 * end of the sending window goes back to SCHEDULED for the next window.
 */
@Getter
@Setter
@Entity
@Table(name = "customer_sms_campaigns")
public class CustomerSmsCampaign {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_SCHEDULED = "SCHEDULED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final int MAX_RECIPIENTS = 5000;
    public static final int MAX_BODY = 600;

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "body", nullable = false, length = 1000)
    private String body;

    @Column(name = "recipient_method", nullable = false, length = 32)
    private String recipientMethod;

    @Column(name = "filter_json", columnDefinition = "text")
    private String filterJson;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "recipients_targeted", nullable = false)
    private int recipientsTargeted;

    @Column(name = "recipients_sent", nullable = false)
    private int recipientsSent;

    @Column(name = "recipients_failed", nullable = false)
    private int recipientsFailed;

    @Column(name = "recipients_skipped", nullable = false)
    private int recipientsSkipped;

    @Column(name = "credits_used", nullable = false)
    private int creditsUsed;

    @Column(name = "created_by_user_id", length = 36)
    private String createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** Touched by the sender while RUNNING; a stale value means the sender died. */
    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;

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
