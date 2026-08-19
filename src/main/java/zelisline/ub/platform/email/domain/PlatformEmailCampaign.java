package zelisline.ub.platform.email.domain;

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
@Table(name = "platform_email_campaigns")
public class PlatformEmailCampaign {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String SEGMENT_STUCK_SIGNUP = "stuck_signup";
    public static final String SEGMENT_UNVERIFIED_OWNERS = "unverified_owners";
    public static final String SEGMENT_SELECTED_TENANTS = "selected_tenants";
    public static final String SEGMENT_SELECTED_USERS = "selected_users";

    public static final int MAX_RECIPIENTS = 500;

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "segment_key", nullable = false, length = 32)
    private String segmentKey;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "body_markdown", nullable = false, columnDefinition = "text")
    private String bodyMarkdown;

    @Column(name = "cta_label", nullable = false, length = 120)
    private String ctaLabel;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "recipients_targeted", nullable = false)
    private int recipientsTargeted;

    @Column(name = "recipients_sent", nullable = false)
    private int recipientsSent;

    @Column(name = "recipients_failed", nullable = false)
    private int recipientsFailed;

    @Column(name = "recipients_skipped", nullable = false)
    private int recipientsSkipped;

    @Column(name = "created_by_super_admin_id", length = 36)
    private String createdBySuperAdminId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (status == null || status.isBlank()) {
            status = STATUS_DRAFT;
        }
        if (ctaLabel == null || ctaLabel.isBlank()) {
            ctaLabel = "Continue setup";
        }
    }

    @PreUpdate
    void onUpdate() {
        /* counts/status only */
    }
}
