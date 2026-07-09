package zelisline.ub.marketplace.domain;

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
@Table(name = "marketplace_supplier_price_offers")
public class MarketplaceSupplierPriceOffer {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "marketplace_supplier_id", nullable = false, length = 36)
    private String marketplaceSupplierId;

    @Column(name = "product_id", nullable = false, length = 36)
    private String productId;

    @Column(name = "package_size", nullable = false, precision = 14, scale = 4)
    private BigDecimal packageSize;

    @Column(name = "package_unit", nullable = false, length = 32)
    private String packageUnit;

    @Column(name = "region_code", length = 32)
    private String regionCode;

    @Column(name = "min_qty", nullable = false, precision = 14, scale = 4)
    private BigDecimal minQty = BigDecimal.ONE;

    @Column(name = "unit_price", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "available", nullable = false)
    private boolean available = true;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

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
        if (effectiveFrom == null) {
            effectiveFrom = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
