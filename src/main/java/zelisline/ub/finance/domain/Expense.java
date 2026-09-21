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
@Table(name = "expenses")
public class Expense {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "category_type", nullable = false, length = 16)
    private String categoryType;

    /** manual | recurring | payroll | drawer */
    @Column(name = "source", nullable = false, length = 32)
    private String source = "manual";

    /** Soft taxonomy: rent, utilities, salaries, … */
    @Column(name = "category_code", length = 32)
    private String categoryCode;

    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(name = "payment_method", nullable = false, length = 32)
    private String paymentMethod;

    /** Optional M-Pesa destination for Send Money (manual expenses). */
    @Column(name = "vendor_mpesa_number", length = 32)
    private String vendorMpesaNumber;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "include_in_cash_drawer", nullable = false)
    private boolean includeInCashDrawer;

    /** posted | pending_approval | rejected */
    @Column(name = "approval_status", nullable = false, length = 32)
    private String approvalStatus = "posted";

    @Column(name = "approved_by", length = 36)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_expires_at")
    private Instant approvalExpiresAt;

    @Column(name = "receipt_s3_key", length = 500)
    private String receiptS3Key;

    @Column(name = "expense_ledger_account_id", nullable = false, length = 36)
    private String expenseLedgerAccountId;

    @Column(name = "journal_entry_id", length = 36)
    private String journalEntryId;

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

