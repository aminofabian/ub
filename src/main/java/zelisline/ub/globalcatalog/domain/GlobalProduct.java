package zelisline.ub.globalcatalog.domain;

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

@Getter
@Setter
@Entity
@Table(
        name = "global_products",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_global_products_dedup_barcode",
                columnNames = {"catalog_id", "dedup_barcode"}))
public class GlobalProduct {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "catalog_id", nullable = false, length = 36)
    private String catalogId;

    @Column(name = "global_category_id", length = 36)
    private String globalCategoryId;

    @Column(name = "sku_template", length = 191)
    private String skuTemplate;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "brand", length = 255)
    private String brand;

    @Column(name = "size", length = 50)
    private String size;

    /** Option label from the source tenant (e.g. Large, Tray) — flat metadata, not a parent link. */
    @Column(name = "variant_name", length = 255)
    private String variantName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "barcode", length = 191)
    private String barcode;

    @Column(name = "unit_type", nullable = false, length = 16)
    private String unitType = "each";

    @Column(name = "is_weighed", nullable = false)
    private boolean weighed;

    @Column(name = "is_sellable", nullable = false)
    private boolean sellable = true;

    @Column(name = "is_stocked", nullable = false)
    private boolean stocked = true;

    /**
     * True when the source SKU was a package/tray variant. Linked children keep
     * {@code is_stocked=false} on adopt; orphaned package rows fall back to flat SKUs.
     */
    @Column(name = "is_package_variant", nullable = false)
    private boolean packageVariant;

    /**
     * Parent global product when this row is a variant/selling-unit of another template.
     * Mirrors tenant {@code items.variant_of_item_id}; remapped on promote, applied on adopt.
     */
    @Column(name = "variant_of_global_product_id", length = 36)
    private String variantOfGlobalProductId;

    @Column(name = "packaging_unit_name", length = 255)
    private String packagingUnitName;

    @Column(name = "packaging_unit_qty", precision = 14, scale = 4)
    private BigDecimal packagingUnitQty;

    @Column(name = "recommended_buying_price", precision = 14, scale = 2)
    private BigDecimal recommendedBuyingPrice;

    @Column(name = "recommended_selling_price", precision = 14, scale = 2)
    private BigDecimal recommendedSellingPrice;

    @Column(name = "suggested_margin_pct", precision = 5, scale = 2)
    private BigDecimal suggestedMarginPct;

    @Column(name = "default_reorder_level", precision = 14, scale = 4)
    private BigDecimal defaultReorderLevel;

    @Column(name = "default_reorder_qty", precision = 14, scale = 4)
    private BigDecimal defaultReorderQty;

    @Column(name = "default_min_stock_level", precision = 14, scale = 4)
    private BigDecimal defaultMinStockLevel;

    @Column(name = "has_expiry", nullable = false)
    private boolean hasExpiry;

    @Column(name = "expires_after_days")
    private Integer expiresAfterDays;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    /** Cloudinary (or local) public id for {@link #imageUrl}; used for explicit clear/destroy. */
    @Column(name = "image_public_id", length = 512)
    private String imagePublicId;

    /**
     * Unique barcode among non-archived rows in a catalog. NULL when archived or barcode blank
     * so archived/empty barcodes do not collide under the unique index.
     */
    @Column(name = "dedup_barcode", length = 191)
    private String dedupBarcode;

    @Column(name = "item_type_key_hint", length = 64)
    private String itemTypeKeyHint = "goods";

    @Column(name = "status", nullable = false, length = 16)
    private String status = GlobalProductStatus.PUBLISHED;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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
        refreshDedupBarcode();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        refreshDedupBarcode();
    }

    /** Keeps {@link #dedupBarcode} in sync for Hibernate create-drop / app-level safety. */
    public void refreshDedupBarcode() {
        if (GlobalProductStatus.ARCHIVED.equals(status) || barcode == null || barcode.isBlank()) {
            dedupBarcode = null;
            return;
        }
        dedupBarcode = barcode.trim();
    }
}
