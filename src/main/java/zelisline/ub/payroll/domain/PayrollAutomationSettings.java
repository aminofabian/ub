package zelisline.ub.payroll.domain;

import java.time.Instant;

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
@Table(name = "payroll_automation_settings")
public class PayrollAutomationSettings {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "automation_mode", nullable = false, length = 32)
    private String automationMode = PayrollAutomationMode.AUTO_PAY;

    @Column(name = "pay_day_of_month", nullable = false)
    private int payDayOfMonth = 28;

    @Column(name = "auto_pay_times_json", length = 512)
    private String autoPayTimesJson;

    @Column(name = "auto_pay_last_run_slot", length = 32)
    private String autoPayLastRunSlot;

    @Column(name = "apply_statutory", nullable = false)
    private boolean applyStatutory;

    @Column(name = "post_expense", nullable = false)
    private boolean postExpense = true;

    @Column(name = "payment_method", nullable = false, length = 32)
    private String paymentMethod = "mpesa_manual";

    @Column(name = "branch_id", length = 36)
    private String branchId;

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
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public static PayrollAutomationSettings disabledFor(String businessId) {
        PayrollAutomationSettings row = new PayrollAutomationSettings();
        row.setBusinessId(businessId);
        row.setEnabled(false);
        row.setAutomationMode(PayrollAutomationMode.AUTO_PAY);
        row.setPayDayOfMonth(28);
        return row;
    }
}
