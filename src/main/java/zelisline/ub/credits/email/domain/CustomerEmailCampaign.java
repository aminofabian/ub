package zelisline.ub.credits.email.domain;

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
@Table(name = "customer_email_campaigns")
public class CustomerEmailCampaign {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String METHOD_SPECIFIC = "specific";
    public static final String METHOD_FILTERED = "filtered";
    public static final String METHOD_ALL_ELIGIBLE = "all_eligible";

    public static final int MAX_RECIPIENTS = 500;

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "subject", nullable = false)
    private String subject;

    @Column(name = "body_html", nullable = false, columnDefinition = "text")
    private String bodyHtml;

    @Column(name = "recipient_method", nullable = false, length = 32)
    private String recipientMethod;

    @Column(name = "filter_json", columnDefinition = "text")
    private String filterJson;

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

    @Column(name = "created_by_user_id", length = 36)
    private String createdByUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
