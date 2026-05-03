package zelisline.ub.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
@Table(name = "cash_drawer_daily_summaries")
public class CashDrawerDailySummary {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "shift_id", nullable = false, length = 36)
    private String shiftId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "opening_cash", nullable = false, precision = 14, scale = 2)
    private BigDecimal openingCash;

    @Column(name = "cash_sales", nullable = false, precision = 14, scale = 2)
    private BigDecimal cashSales = BigDecimal.ZERO;

    @Column(name = "cash_refunds", nullable = false, precision = 14, scale = 2)
    private BigDecimal cashRefunds = BigDecimal.ZERO;

    @Column(name = "drawer_expenses", nullable = false, precision = 14, scale = 2)
    private BigDecimal drawerExpenses = BigDecimal.ZERO;

    @Column(name = "supplier_cash_from_drawer", nullable = false, precision = 14, scale = 2)
    private BigDecimal supplierCashFromDrawer = BigDecimal.ZERO;

    @Column(name = "expected_closing_cash", nullable = false, precision = 14, scale = 2)
    private BigDecimal expectedClosingCash;

    @Column(name = "counted_closing_cash", precision = 14, scale = 2)
    private BigDecimal countedClosingCash;

    @Column(name = "closing_variance", precision = 14, scale = 2)
    private BigDecimal closingVariance;

    @Column(name = "snapshot_json", columnDefinition = "json")
    private String snapshotJson;

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
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}

