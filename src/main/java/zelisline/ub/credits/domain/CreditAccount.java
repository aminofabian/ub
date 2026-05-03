package zelisline.ub.credits.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "credit_accounts")
public class CreditAccount {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    @Column(name = "balance_owed", nullable = false, precision = 14, scale = 2)
    private BigDecimal balanceOwed = BigDecimal.ZERO;

    @Column(name = "wallet_balance", nullable = false, precision = 14, scale = 2)
    private BigDecimal walletBalance = BigDecimal.ZERO;

    @Column(name = "loyalty_points", nullable = false)
    private int loyaltyPoints;

    @Column(name = "credit_limit", precision = 14, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

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
