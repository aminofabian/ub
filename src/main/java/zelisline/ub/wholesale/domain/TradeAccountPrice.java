package zelisline.ub.wholesale.domain;

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

/** One product's price for one buyer's account (P4). It replaces the shop's price for that buyer. */
@Getter
@Setter
@Entity
@Table(
        name = "trade_account_prices",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_trade_account_prices", columnNames = {"trade_account_id", "item_id"}))
public class TradeAccountPrice {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "trade_account_id", nullable = false, length = 36)
    private String tradeAccountId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
