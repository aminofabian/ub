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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * A purchasable pack shape for a catalog item (e.g. pack of 12 / 18 / 48).
 *
 * <p>Unit purchase is <em>not</em> a row: the absence of a selected pack option
 * means 1× base unit at the unit price. Buying by pack is an explicit choice.
 */
@Getter
@Setter
@Entity
@Table(
        name = "item_pack_options",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_item_pack_options_shape",
                columnNames = {"business_id", "item_id", "units_per_pack", "pack_unit"}))
public class ItemPackOption {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "label", length = 255)
    private String label;

    @Column(name = "pack_unit", nullable = false, length = 32)
    private String packUnit;

    /** Pieces / kg / etc. per one pack. Must be &gt; 1 (DB CHECK). */
    @Column(name = "units_per_pack", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitsPerPack;

    /** Wholesale / buy price for ONE pack. Optional; null = ask. */
    @Column(name = "default_pack_price", precision = 14, scale = 4)
    private BigDecimal defaultPackPrice;

    @Column(name = "barcode", length = 191)
    private String barcode;

    @Column(name = "sku_suffix", length = 64)
    private String skuSuffix;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active = true;

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
