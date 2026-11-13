package zelisline.ub.serviceops.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * One line of a staff member's running balance (ADR 0014). Rows are never edited or deleted:
 * a mistake is corrected by another row. The balance is the sum of {@link #amount}.
 */
@Getter
@Setter
@Entity
@Table(
        name = "staff_ledger_entries",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_staff_ledger_dedupe",
                columnNames = {"business_id", "dedupe_key"}))
public class StaffLedgerEntry {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** Branch of the sale or payout; {@code null} for an adjustment. */
    @Column(name = "branch_id", length = 36)
    private String branchId;

    @Column(name = "staff_profile_id", nullable = false, length = 36)
    private String staffProfileId;

    /** See {@link StaffLedgerKinds}. */
    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    /** Which liability this row moves: {@code COMMISSION} or {@code TIPS}. */
    @Column(name = "bucket", nullable = false, length = 16)
    private String bucket;

    /** Signed, KES. Earnings positive; reversals and payouts negative. */
    @Column(name = "amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** What a commission was calculated on (the line total after discount, excluding VAT). */
    @Column(name = "base_amount", precision = 14, scale = 2)
    private BigDecimal baseAmount;

    /** The rule as it was when the money was earned, e.g. {@code PERCENT 40.00 (staff)}. */
    @Column(name = "rule", length = 64)
    private String rule;

    /** {@code SERVICE} or {@code PRODUCT} for commission rows. */
    @Column(name = "source", length = 16)
    private String source;

    @Column(name = "sale_id", length = 36)
    private String saleId;

    @Column(name = "sale_item_id", length = 36)
    private String saleItemId;

    /** The row this one cancels, for reversals. */
    @Column(name = "reverses_id", length = 36)
    private String reversesId;

    /** {@code DRAWER}, {@code MPESA} or {@code OTHER} for payouts. */
    @Column(name = "payout_method", length = 16)
    private String payoutMethod;

    @Column(name = "reference", length = 191)
    private String reference;

    /** The till shift a drawer payout came out of. */
    @Column(name = "shift_id", length = 36)
    private String shiftId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "journal_entry_id", length = 36)
    private String journalEntryId;

    /** Unique per business; makes replays and double taps write nothing twice. */
    @Column(name = "dedupe_key", nullable = false, length = 191)
    private String dedupeKey;

    @Column(name = "created_by", length = 36)
    private String createdBy;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (occurredAt == null) {
            occurredAt = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }
}
