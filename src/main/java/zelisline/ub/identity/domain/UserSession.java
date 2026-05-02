package zelisline.ub.identity.domain;

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
@Table(name = "user_sessions")
public class UserSession {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "access_token_jti", nullable = false, unique = true, length = 36)
    private String accessTokenJti;

    @Column(name = "refresh_token_hash", nullable = false, unique = true, length = 64)
    private String refreshTokenHash;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "ip", length = 45)
    private String ip;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "refresh_expires_at", nullable = false)
    private Instant refreshExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "rotated_to", length = 36)
    private String rotatedToId;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (issuedAt == null) {
            issuedAt = now;
        }
    }
}
