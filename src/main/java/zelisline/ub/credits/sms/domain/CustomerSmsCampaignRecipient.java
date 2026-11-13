package zelisline.ub.credits.sms.domain;

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
 * One customer in an SMS campaign. SENDING is written before the provider call, so a
 * sender that dies mid-call leaves a row that is failed on recovery, never re-sent.
 */
@Getter
@Setter
@Entity
@Table(name = "customer_sms_campaign_recipients")
public class CustomerSmsCampaignRecipient {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENDING = "SENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "campaign_id", nullable = false, length = 36)
    private String campaignId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    @Column(name = "customer_name", length = 500)
    private String customerName;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "skip_reason", length = 64)
    private String skipReason;

    @Column(name = "error", length = 500)
    private String error;

    @Column(name = "parts", nullable = false)
    private int parts;

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
