package zelisline.ub.wholesale.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * A buyer's account with one supplier's shop (ADR 0038, P1-6). Anyone may order and pay up front;
 * an approved account is what lets the supplier sell to that buyer on credit.
 */
@Getter
@Setter
@Entity
@Table(
        name = "trade_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_trade_accounts_phone", columnNames = {"business_id", "phone_key"}))
public class TradeAccount {

    public static final String APPLIED = "applied";
    public static final String APPROVED = "approved";
    public static final String DECLINED = "declined";
    public static final String SUSPENDED = "suspended";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** The phone number in one spelling, so the same number always finds the same account. */
    @Column(name = "phone_key", nullable = false, length = 32)
    private String phoneKey;

    @Column(name = "buyer_phone", nullable = false, length = 64)
    private String buyerPhone;

    @Column(name = "buyer_name", nullable = false, length = 255)
    private String buyerName;

    @Column(name = "business_name", length = 255)
    private String businessName;

    @Column(name = "note", length = 1000)
    private String note;

    @Column(name = "status", nullable = false, length = 16)
    private String status = APPLIED;

    @Column(name = "credit_limit", precision = 14, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "terms_days")
    private Integer termsDays;

    /** The shop this account belongs to, when the buyer was a signed-in marketplace buyer. */
    @Column(name = "buyer_business_id", length = 36)
    private String buyerBusinessId;

    /** A trade discount taken off every price for this buyer. Only for an account that names a shop. */
    @Column(name = "discount_percent", precision = 5, scale = 2)
    private BigDecimal discountPercent;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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
