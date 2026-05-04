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

/**
 * Phase 7 Slice 2 summary row. Composite PK matches the MV's natural unique key
 * {@code (business_id, branch_id, day, item_id)} and powers the per-day rollup the
 * sales register and profit-by-item reports consume.
 */
@Getter
@Setter
@Entity
@Table(name = "mv_sales_daily")
public class MvSalesDaily {

    @EmbeddedId
    private Key id;

    @Column(name = "qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal qty;

    @Column(name = "revenue", nullable = false, precision = 18, scale = 2)
    private BigDecimal revenue;

    @Column(name = "cost", nullable = false, precision = 18, scale = 2)
    private BigDecimal cost;

    @Column(name = "profit", nullable = false, precision = 18, scale = 2)
    private BigDecimal profit;

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

        @Column(name = "business_day", nullable = false)
        private LocalDate day;

        @Column(name = "item_id", nullable = false, length = 36)
        private String itemId;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(businessId, key.businessId)
                    && Objects.equals(branchId, key.branchId)
                    && Objects.equals(day, key.day)
                    && Objects.equals(itemId, key.itemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(businessId, branchId, day, itemId);
        }
    }
}
