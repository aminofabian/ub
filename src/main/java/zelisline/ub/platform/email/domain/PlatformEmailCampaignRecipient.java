package zelisline.ub.platform.email.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "platform_email_campaign_recipients")
public class PlatformEmailCampaignRecipient {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static final String KIND_VERIFY = "verify";
    public static final String KIND_HUB = "hub";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "campaign_id", nullable = false, length = 36)
    private String campaignId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "continue_kind", nullable = false, length = 16)
    private String continueKind;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "error", length = 1000)
    private String error;

    @Column(name = "sent_at")
    private Instant sentAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (status == null || status.isBlank()) {
            status = STATUS_PENDING;
        }
    }
}
