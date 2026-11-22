package zelisline.ub.pricing.domain;

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

/** A lower unit price from a set quantity upwards, for online orders (ADR 0038, P1-5). */
@Getter
@Setter
@Entity
@Table(
        name = "item_price_breaks",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_item_price_breaks",
                columnNames = {"business_id", "item_id", "min_qty"}))
public class ItemPriceBreak {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "min_qty", nullable = false, precision = 14, scale = 4)
    private BigDecimal minQty;

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
