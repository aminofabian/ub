package zelisline.ub.marketplace.domain;

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

@Getter
@Setter
@Entity
@Table(name = "supplier_users")
public class SupplierUser {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "marketplace_supplier_id", nullable = false, length = 36)
    private String marketplaceSupplierId;

    @Column(name = "email", nullable = false, unique = true, length = 191)
    private String email;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** admin — all supplier portal permissions in v1 */
    @Column(name = "role_key", nullable = false, length = 32)
    private String roleKey = SupplierUserRoles.ADMIN;

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
        email = normalizeEmail(email);
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        email = normalizeEmail(email);
    }

    private static String normalizeEmail(String value) {
        return value == null ? null : value.trim().toLowerCase();
    }
}
