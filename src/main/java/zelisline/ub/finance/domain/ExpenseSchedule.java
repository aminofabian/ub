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
import zelisline.ub.finance.FinanceConstants;

@Getter
@Setter
@Entity
@Table(name = "expense_schedules")
public class ExpenseSchedule {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "category_type", nullable = false, length = 16)
    private String categoryType;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_method", nullable = false, length = 32)
    private String paymentMethod;

    @Column(name = "include_in_cash_drawer", nullable = false)
    private boolean includeInCashDrawer;

    @Column(name = "receipt_s3_key", length = 500)
    private String receiptS3Key;

    @Column(name = "expense_ledger_account_id", nullable = false, length = 36)
    private String expenseLedgerAccountId;

    @Column(name = "frequency", nullable = false, length = 16)
    private String frequency;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_generated_on")
    private LocalDate lastGeneratedOn;

    @Column(name = "automation_mode", nullable = false, length = 16)
    private String automationMode = FinanceConstants.AUTOMATION_MODE_AUTO_POST;

    @Column(name = "vendor_contact_name", length = 128)
    private String vendorContactName;

    @Column(name = "vendor_phone", length = 32)
    private String vendorPhone;

    @Column(name = "vendor_mpesa_number", length = 32)
    private String vendorMpesaNumber;

    @Column(name = "vendor_lease_note", length = 1000)
    private String vendorLeaseNote;

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

