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
 * What is tapped on the order screen. A {@code DISH} is made to order and has no stock of its
 * own; an {@code ITEM} points at a stocked product, which is sold through the retail path.
 */
@Getter
@Setter
@Entity
@Table(name = "menu_items")
public class MenuItem {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "category_id", length = 36)
    private String categoryId;

    @Column(name = "name", nullable = false, length = 191)
    private String name;

    /** {@code DISH} or {@code ITEM}. */
    /** A line or two for the customer: what it is, what comes with it. Shown on the shop's own page. */
    @Column(name = "description", length = 500)
    private String description;

    /** Where the dish's picture is served from; null when it has none. */
    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    /** The media store's own id for that picture, so it can be removed when replaced. */
    @Column(name = "image_public_id", length = 512)
    private String imagePublicId;

    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    /** The product sold, when {@code kind = ITEM}. */
    @Column(name = "item_id", length = 36)
    private String itemId;

    /** Price of a dish before modifiers. Unused for {@code ITEM}: a product keeps its shelf price. */
    @Column(name = "price", precision = 14, scale = 2)
    private BigDecimal price;

    @Column(name = "tax_rate_id", length = 36)
    private String taxRateId;

    /** Overrides the category station; null on both means the one implicit kitchen for a dish. */
    @Column(name = "station_id", length = 36)
    private String stationId;

    /** False while the kitchen has run out. */
    @Column(name = "available", nullable = false)
    private boolean available = true;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** Served only between these minutes after midnight, Nairobi time (breakfast until 11:00). Both null: all day. */
    @Column(name = "serve_from_minute")
    private Integer serveFromMinute;

    @Column(name = "serve_to_minute")
    private Integer serveToMinute;

    /** A dish's price during happy hour: between the two minutes, on the days named. */
    @Column(name = "happy_price", precision = 14, scale = 2)
    private BigDecimal happyPrice;

    @Column(name = "happy_from_minute")
    private Integer happyFromMinute;

    @Column(name = "happy_to_minute")
    private Integer happyToMinute;

    /** Days happy hour runs, as digits 1 (Monday) to 7 (Sunday); null or empty means every day. */
    @Column(name = "happy_days", length = 7)
    private String happyDays;

    @Column(name = "deleted_at")
    private Instant deletedAt;

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
