package zelisline.ub.catalog.domain;

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
@Table(name = "items")
public class Item {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "sku", nullable = false, length = 191)
    private String sku;

    @Column(name = "barcode", length = 191)
    private String barcode;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "description", length = 10_000)
    private String description;

    @Column(name = "variant_of_item_id", length = 36)
    private String variantOfItemId;

    @Column(name = "variant_name", length = 255)
    private String variantName;

    @Column(name = "category_id", length = 36)
    private String categoryId;

    @Column(name = "aisle_id", length = 36)
    private String aisleId;

    @Column(name = "item_type_id", nullable = false, length = 36)
    private String itemTypeId;

    @Column(name = "unit_type", nullable = false, length = 16)
    private String unitType = "each";

    @Column(name = "is_weighed", nullable = false)
    private boolean weighed;

    @Column(name = "is_sellable", nullable = false)
    private boolean sellable = true;

    @Column(name = "is_stocked", nullable = false)
    private boolean stocked = true;

    @Column(name = "current_stock", nullable = false, precision = 14, scale = 4)
    private BigDecimal currentStock = BigDecimal.ZERO;

    @Column(name = "packaging_unit_name", length = 255)
    private String packagingUnitName;

    @Column(name = "packaging_unit_qty", precision = 14, scale = 4)
    private BigDecimal packagingUnitQty;

    @Column(name = "bundle_qty")
    private Integer bundleQty;

    @Column(name = "bundle_price", precision = 14, scale = 2)
    private BigDecimal bundlePrice;

    @Column(name = "bundle_name", length = 255)
    private String bundleName;

    @Column(name = "min_stock_level", precision = 14, scale = 4)
    private BigDecimal minStockLevel;

    @Column(name = "reorder_level", precision = 14, scale = 4)
    private BigDecimal reorderLevel;

    @Column(name = "reorder_qty", precision = 14, scale = 4)
    private BigDecimal reorderQty;

    @Column(name = "expires_after_days")
    private Integer expiresAfterDays;

    @Column(name = "has_expiry", nullable = false)
    private boolean hasExpiry;

    @Column(name = "image_key", length = 500)
    private String imageKey;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

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
