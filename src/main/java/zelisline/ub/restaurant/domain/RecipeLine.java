package zelisline.ub.restaurant.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One ingredient of a dish, or of a modifier option ("double patty" uses one more patty).
 * Exactly one of {@code menuItemId} and {@code modifierOptionId} is set. The quantity is in the
 * ingredient's own stock unit and is what ends up on the plate; {@code yieldPercent} says how
 * much of what is taken from stock survives trimming and cooking.
 */
@Getter
@Setter
@Entity
@Table(name = "recipe_lines")
public class RecipeLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "menu_item_id", length = 36)
    private String menuItemId;

    @Column(name = "modifier_option_id", length = 36)
    private String modifierOptionId;

    /** Set instead of the other two when this is the recipe of a prepared batch: the stocked item it makes. */
    @Column(name = "produced_item_id", length = 36)
    private String producedItemId;

    /** The ingredient: a stocked catalogue item, usually not for sale. */
    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    /**
     * A stand-in for the ingredient, used when the ingredient has run out and this has not: the
     * same quantity, in this product's own stock unit. Null when there is none.
     */
    @Column(name = "substitute_item_id", length = 36)
    private String substituteItemId;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    /** 1 to 100; null means nothing is lost. 75 means 100 g on the plate takes 133.33 g from stock. */
    @Column(name = "yield_percent", precision = 5, scale = 2)
    private BigDecimal yieldPercent;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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
