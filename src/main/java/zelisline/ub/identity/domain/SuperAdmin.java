package zelisline.ub.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Platform-level operator (PHASE_1_PLAN.md §2.1, §2.4 invariant 6).
 *
 * <p>Lives in its own table — never in {@code users} — because a super-admin
 * has no {@code business_id}. Email uniqueness is platform-wide.
 */
@Getter
@Setter
@Entity
@Table(name = "super_admins")
public class SuperAdmin {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "email", nullable = false, unique = true, length = 191)
    private String email;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "mfa_secret")
    private String mfaSecret;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        email = normalize(email);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        email = normalize(email);
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }
}
