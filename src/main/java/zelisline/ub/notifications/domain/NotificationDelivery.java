package zelisline.ub.notifications.domain;

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
@Table(name = "notification_deliveries")
public class NotificationDelivery {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public static final String CHANNEL_IN_APP = "IN_APP";
    public static final String CHANNEL_SMS = "SMS";
    public static final String CHANNEL_WHATSAPP = "WHATSAPP";
    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_WEB_PUSH = "WEB_PUSH";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "notification_id", nullable = false, length = 36)
    private String notificationId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "channel", nullable = false, length = 16)
    private String channel;

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    @Column(name = "provider", length = 32)
    private String provider;

    @Column(name = "provider_message_id", length = 128)
    private String providerMessageId;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

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
