package zelisline.ub.marketplace.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "supplier_portal_claim_invites")
@Getter
@Setter
public class SupplierPortalClaimInvite {

    @Id
    @Column(length = 36, nullable = false)
    private String id;

    @Column(name = "marketplace_supplier_id", length = 36, nullable = false)
    private String marketplaceSupplierId;

    @Column(name = "code_hash", length = 64, nullable = false)
    private String codeHash;

    @Column(length = 32)
    private String phone;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "setup_token_hash", length = 64)
    private String setupTokenHash;

    @Column(name = "setup_token_expires_at")
    private Instant setupTokenExpiresAt;

    @Column(name = "created_by_actor_id", length = 36)
    private String createdByActorId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
