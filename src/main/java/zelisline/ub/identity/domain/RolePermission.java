package zelisline.ub.identity.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Join row mapping a role to a permission (PHASE_1_PLAN.md §2.1).
 *
 * <p>Composite primary key on {@code (role_id, permission_id)}; uses
 * {@code ON DELETE CASCADE} from the role side at the schema level so deleting
 * a role removes its grants automatically.
 */
@Getter
@Setter
@Entity
@Table(name = "role_permissions")
public class RolePermission {

    @EmbeddedId
    private Id id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {

        @Column(name = "role_id", nullable = false, length = 36)
        private String roleId;

        @Column(name = "permission_id", nullable = false, length = 36)
        private String permissionId;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Id that)) {
                return false;
            }
            return Objects.equals(roleId, that.roleId)
                    && Objects.equals(permissionId, that.permissionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roleId, permissionId);
        }
    }
}
