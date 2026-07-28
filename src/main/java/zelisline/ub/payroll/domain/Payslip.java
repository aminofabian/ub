package zelisline.ub.payroll.domain;

import java.math.BigDecimal;
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
@Table(name = "payslips")
public class Payslip {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "staff_profile_id", nullable = false, length = 36)
    private String staffProfileId;

    @Column(name = "period_year", nullable = false)
    private int periodYear;

    @Column(name = "period_month", nullable = false)
    private int periodMonth;

    @Column(name = "base_salary", nullable = false, precision = 14, scale = 2)
    private BigDecimal baseSalary;

    @Column(name = "advances_deducted", nullable = false, precision = 14, scale = 2)
    private BigDecimal advancesDeducted = BigDecimal.ZERO;

    @Column(name = "other_deductions", nullable = false, precision = 14, scale = 2)
    private BigDecimal otherDeductions = BigDecimal.ZERO;

    @Column(name = "net_paid", nullable = false, precision = 14, scale = 2)
    private BigDecimal netPaid;

    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    @Column(name = "note", length = 500)
    private String note;

    /** GL / expense hook — unused in MVP. */
    @Column(name = "expense_id", length = 36)
    private String expenseId;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

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
        if (advancesDeducted == null) {
            advancesDeducted = BigDecimal.ZERO;
        }
        if (otherDeductions == null) {
            otherDeductions = BigDecimal.ZERO;
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
