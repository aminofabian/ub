package zelisline.ub.finance.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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
@Table(name = "profit_pocket_days")
public class ProfitPocketDay {

    public static final String STATUS_RECORDED = "recorded";
    public static final String STATUS_SKIPPED = "skipped";

    public static final String SOURCE_MANUAL = "manual";
    public static final String SOURCE_CASH = "cash";
    public static final String SOURCE_MIXED = "mixed";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** Empty string means the whole business, not one branch. */
    @Column(name = "branch_key", nullable = false, length = 36)
    private String branchKey = "";

    @Column(name = "pocket_date", nullable = false)
    private LocalDate pocketDate;

    @Column(name = "profit_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal profitAmount;

    @Column(name = "pocketed_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal pocketedAmount = BigDecimal.ZERO;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    @Column(name = "skip_reason", length = 240)
    private String skipReason;

    @Column(name = "source", nullable = false, length = 16)
    private String source = SOURCE_MANUAL;

    @Column(name = "above_profit", nullable = false)
    private boolean aboveProfit;

    @Column(name = "revisions_json", columnDefinition = "TEXT")
    private String revisionsJson;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (branchKey == null) {
            branchKey = "";
        }
        if (pocketedAmount == null) {
            pocketedAmount = BigDecimal.ZERO;
        }
        if (source == null || source.isBlank()) {
            source = SOURCE_MANUAL;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
