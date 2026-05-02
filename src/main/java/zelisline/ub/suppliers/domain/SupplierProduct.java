package zelisline.ub.suppliers.domain;

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
@Table(name = "supplier_products")
public class SupplierProduct {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "supplier_id", nullable = false, length = 36)
    private String supplierId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(name = "is_primary", nullable = false)
    private boolean primaryLink;

    @Column(name = "supplier_sku", length = 191)
    private String supplierSku;

    @Column(name = "default_cost_price", precision = 14, scale = 4)
    private BigDecimal defaultCostPrice;

    @Column(name = "pack_size", precision = 14, scale = 4)
    private BigDecimal packSize;

    @Column(name = "pack_unit", length = 32)
    private String packUnit;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(name = "min_order_qty", precision = 14, scale = 4)
    private BigDecimal minOrderQty;

    @Column(name = "last_cost_price", precision = 14, scale = 4)
    private BigDecimal lastCostPrice;

    @Column(name = "last_purchase_at")
    private Instant lastPurchaseAt;

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
