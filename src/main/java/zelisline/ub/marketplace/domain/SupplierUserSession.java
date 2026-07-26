package zelisline.ub.marketplace.domain;

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
@Table(name = "supplier_user_sessions")
public class SupplierUserSession {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "supplier_user_id", nullable = false, length = 36)
    private String supplierUserId;

    @Column(name = "marketplace_supplier_id", nullable = false, length = 36)
    private String marketplaceSupplierId;

    @Column(name = "access_token_jti", nullable = false, unique = true, length = 36)
    private String accessTokenJti;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "ip", length = 45)
    private String ip;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
        if (issuedAt == null) {
            issuedAt = now;
        }
        if (lastSeenAt == null) {
            lastSeenAt = now;
        }
    }
}
