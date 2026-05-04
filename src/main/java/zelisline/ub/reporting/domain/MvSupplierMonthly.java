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
@Table(name = "mv_supplier_monthly")
public class MvSupplierMonthly {

    @EmbeddedId
    private Key id;

    @Column(name = "spend", nullable = false, precision = 18, scale = 2)
    private BigDecimal spend;

    @Column(name = "qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal qty;

    @Column(name = "invoice_count", nullable = false)
    private long invoiceCount;

    @Column(name = "wastage_qty", nullable = false, precision = 18, scale = 4)
    private BigDecimal wastageQty;

    @Column(name = "refreshed_at", nullable = false)
    private Instant refreshedAt;

    @Getter
    @Setter
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "business_id", nullable = false, length = 36)
        private String businessId;

        @Column(name = "supplier_id", nullable = false, length = 36)
        private String supplierId;

        @Column(name = "calendar_month", nullable = false)
        private LocalDate calendarMonth;

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(businessId, key.businessId)
                    && Objects.equals(supplierId, key.supplierId)
                    && Objects.equals(calendarMonth, key.calendarMonth);
        }

        @Override
        public int hashCode() {
            return Objects.hash(businessId, supplierId, calendarMonth);
        }
    }
}
