package zelisline.ub.platform.pageseal.domain;

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
@Table(name = "page_seal_challenges")
public class PageSealChallenge {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    /** supplier_slug | customer_tab */
    @Column(name = "scope", nullable = false, length = 32)
    private String scope;

    @Column(name = "subject_id", nullable = false, length = 64)
    private String subjectId;

    @Column(name = "phone", nullable = false, length = 32)
    private String phone;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 5;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "setup_token_hash", length = 64)
    private String setupTokenHash;

    @Column(name = "setup_token_expires_at")
    private Instant setupTokenExpiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

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
