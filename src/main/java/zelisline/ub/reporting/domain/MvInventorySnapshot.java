package zelisline.ub.reporting.domain;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "mv_inventory_snapshot")
public class MvInventorySnapshot {

    @EmbeddedId
    private Key id;

    @Column(name = "qty_on_hand", nullable = false, precision = 18, scale = 4)
    private BigDecimal qtyOnHand;

    @Column(name = "fifo_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal fifoValue;

    @Column(name = "earliest_expiry")
    private LocalDate earliestExpiry;

    @Column(name = "refreshed_at", nullable = false)
    private Instant refreshedAt;

    @Getter
    @Setter
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "business_id", nullable = false, length = 36)
        private String businessId;

        @Column(name = "branch_id", nullable = false, length = 36)
        private String branchId;

        @Column(name = "item_id", nullable = false, length = 36)
        private String itemId;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(businessId, key.businessId)
                    && Objects.equals(branchId, key.branchId)
                    && Objects.equals(itemId, key.itemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(businessId, branchId, itemId);
        }
    }
}
