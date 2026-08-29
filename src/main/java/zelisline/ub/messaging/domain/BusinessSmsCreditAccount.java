package zelisline.ub.messaging.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-tenant SMS credit account (one row per business).
 *
 * <p>Available balance is computed, not stored:
 * {@code available = max(0, allowance - included_used) + purchased_balance} where
 * {@code allowance} is the tier lookup (or {@link #includedOverride} when set).
 * Included allowance resets monthly; purchased balance rolls over.
 */
@Entity
@Table(name = "business_sms_credit_accounts")
@Getter
@Setter
public class BusinessSmsCreditAccount {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "included_used", nullable = false)
    private int includedUsed;

    /** NULL = use tier table; integer = custom monthly cap set by Super Admin. */
    @Column(name = "included_override")
    private Integer includedOverride;

    @Column(name = "purchased_balance", nullable = false)
    private int purchasedBalance;

    /** Highest usage digest already emailed this cycle (null = none, 80, 100). */
    @Column(name = "last_digest_pct")
    private Integer lastDigestPct;

    @Column(name = "cycle_started_at", nullable = false)
    private Instant cycleStartedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (cycleStartedAt == null) {
            cycleStartedAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public int effectiveAllowance(Integer tierAllowance) {
        return includedOverride != null ? includedOverride : (tierAllowance != null ? tierAllowance : 0);
    }

    public int includedRemaining(int allowance) {
        return Math.max(0, allowance - includedUsed);
    }

    public int available(int allowance) {
        return includedRemaining(allowance) + purchasedBalance;
    }
}
