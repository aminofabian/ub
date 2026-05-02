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
 * Catalogue of permission keys ({@code users.create}, {@code business.manage_settings}, …).
 * Loaded as data, not derived from code — see {@code PHASE_1_PLAN.md} §2.2.
 */
@Getter
@Setter
@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "permission_key", nullable = false, unique = true, length = 191)
    private String permissionKey;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

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
        if (permissionKey != null) {
            permissionKey = permissionKey.trim().toLowerCase();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        if (permissionKey != null) {
            permissionKey = permissionKey.trim().toLowerCase();
        }
    }
}
