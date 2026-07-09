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
@Table(name = "marketplace_supplier_products")
public class MarketplaceSupplierProduct {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "marketplace_supplier_id", nullable = false, length = 36)
    private String marketplaceSupplierId;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "barcode", length = 191)
    private String barcode;

    @Column(name = "sku", length = 191)
    private String sku;

    @Column(name = "category_name", length = 255)
    private String categoryName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "pack_size", precision = 14, scale = 4)
    private BigDecimal packSize;

    @Column(name = "pack_unit", length = 32)
    private String packUnit;

    @Column(name = "min_order_qty", precision = 14, scale = 4)
    private BigDecimal minOrderQty;

    /** active | inactive */
    @Column(name = "status", nullable = false, length = 16)
    private String status = MarketplaceSupplierProductStatuses.ACTIVE;

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
