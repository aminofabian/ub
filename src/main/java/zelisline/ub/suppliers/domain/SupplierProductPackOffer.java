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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-link offer of an {@code ItemPackOption} (docs/MULTI_PACK_OPTIONS_SCOPE.md §5.2).
 *
 * <p>Merge rule: an active offer row overrides the pack price for that link; an offer
 * row with {@code active = false} opts the link out of that pack shape. Absence of a row
 * means the option is offered at its item-level default pack price.
 */
@Getter
@Setter
@Entity
@Table(
        name = "supplier_product_pack_offers",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_supplier_product_pack_offers_pair",
                columnNames = {"supplier_product_id", "item_pack_option_id"}))
public class SupplierProductPackOffer {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "supplier_product_id", nullable = false, length = 36)
    private String supplierProductId;

    @Column(name = "item_pack_option_id", nullable = false, length = 36)
    private String itemPackOptionId;

    /** Override price for ONE pack; null = use the item option's default pack price. */
    @Column(name = "pack_price", precision = 14, scale = 4)
    private BigDecimal packPrice;

    @Column(name = "active", nullable = false)
    private boolean active = true;

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
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
