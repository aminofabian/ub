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
 * Per-user item-type (department) restriction.
 *
 * <p>When the {@code grocery_clerk} role is assigned, the catalog API ANDs the
 * user's allowed item types into every {@code /api/v1/items} query so the clerk
 * can only see / invoice items inside their departments. Other roles ignore
 * these rows entirely.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "user_item_types")
public class UserItemType {

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

        @Column(name = "user_id", nullable = false, length = 36)
        private String userId;

        @Column(name = "item_type_id", nullable = false, length = 36)
        private String itemTypeId;

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Id that)) {
                return false;
            }
            return Objects.equals(userId, that.userId)
                    && Objects.equals(itemTypeId, that.itemTypeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, itemTypeId);
        }
    }
}
