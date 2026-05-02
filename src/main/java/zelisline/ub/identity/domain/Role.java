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
 * A role groups permissions. System roles have {@code business_id = NULL} and
 * {@code is_system = TRUE}; tenant-defined roles set both fields. See
 * {@code PHASE_1_PLAN.md} §2.1.
 */
@Getter
@Setter
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    /** {@code NULL} for system-wide roles seeded in Flyway. */
    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "role_key", nullable = false, length = 191)
    private String roleKey;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "is_system", nullable = false)
    private boolean system;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

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
        if (roleKey != null) {
            roleKey = roleKey.trim().toLowerCase();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        if (roleKey != null) {
            roleKey = roleKey.trim().toLowerCase();
        }
    }
}
