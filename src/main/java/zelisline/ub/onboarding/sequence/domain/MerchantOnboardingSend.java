package zelisline.ub.onboarding.sequence.domain;

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
@Table(name = "merchant_onboarding_send")
public class MerchantOnboardingSend {

    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_IN_APP = "IN_APP";
    public static final String CHANNEL_WHATSAPP = "WHATSAPP";

    public static final String STATUS_SENT = "sent";
    public static final String STATUS_SKIPPED = "skipped";
    public static final String STATUS_FAILED = "failed";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "step_key", nullable = false, length = 32)
    private String stepKey;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "skip_reason", length = 64)
    private String skipReason;

    @Column(name = "dedupe_key", nullable = false, length = 128)
    private String dedupeKey;

    @Column(name = "sent_at")
    private Instant sentAt;

    /** How many times a FAILED email has been re-attempted (max 2). */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /** When the next retry is allowed (null = not scheduled / exhausted). */
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        createdAt = Instant.now();
    }
}
