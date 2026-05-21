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
@Table(name = "device_tokens")
public class DeviceToken {

    public static final String PLATFORM_WEB = "WEB";
    public static final String PLATFORM_ANDROID = "ANDROID";
    public static final String PLATFORM_IOS = "IOS";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "platform", nullable = false, length = 16)
    private String platform;

    @Column(name = "token", nullable = false, length = 512)
    private String token;

    @Column(name = "endpoint", length = 1024)
    private String endpoint;

    @Column(name = "p256dh", length = 255)
    private String p256dh;

    @Column(name = "auth_secret", length = 255)
    private String authSecret;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
    }
}
