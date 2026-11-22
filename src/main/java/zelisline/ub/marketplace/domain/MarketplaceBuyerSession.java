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

/** A buyer signed in on the marketplace with a phone code (ADR 0043). Holds a hash of the token only. */
@Getter
@Setter
@Entity
@Table(name = "marketplace_buyer_sessions")
public class MarketplaceBuyerSession {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /** The phone number the code was sent to, as the verification returned it. */
    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
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
