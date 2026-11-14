package zelisline.ub.serviceops.domain;

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
 * Something a service business sells by the visit — a haircut, a massage (ADR 0014).
 *
 * <p>Named {@code ServiceOffering} rather than {@code Service} so it cannot be confused with a
 * Spring {@code @Service}. It is deliberately not a catalog {@code Item}: it has no stock, and
 * it carries a duration and (later) the staff who may perform it.</p>
 */
@Getter
@Setter
@Entity
@Table(
        name = "services",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_services_business_name",
                columnNames = {"business_id", "name_key"}))
public class ServiceOffering {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "category_id", length = 36)
    private String categoryId;

    @Column(name = "name", nullable = false, length = 191)
    private String name;

    /**
     * Lower-cased trimmed name while the row is live, {@code null} once soft-deleted, so the
     * unique key only constrains live rows and a deleted name can be reused.
     */
    @Column(name = "name_key", length = 191)
    private String nameKey;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    /** KES. For a variable-price service this is the suggested price and may be zero. */
    @Column(name = "price", nullable = false, precision = 14, scale = 2)
    private BigDecimal price;

    /** The attendant keys the price at the till (for example braids priced by length). */
    @Column(name = "price_is_variable", nullable = false)
    private boolean priceIsVariable;

    /** Optional per-service commission override; see {@link ServiceCommissionTypes}. */
    @Column(name = "commission_type", length = 16)
    private String commissionType;

    @Column(name = "commission_value", precision = 14, scale = 2)
    private BigDecimal commissionValue;

    /**
     * What the product for one of these costs the shop, as the owner says it. Taken off the price
     * before commission when the shop pays commission after product cost (ADR 0019); null: none.
     */
    @Column(name = "product_cost", precision = 14, scale = 2)
    private BigDecimal productCost;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** The kind of resource this service needs (a chair, a bed); null when any or none will do. */
    @Column(name = "resource_type", length = 24)
    private String resourceType;

    /** A prepaid package of this service: how many sessions it holds. Null when none is offered. */
    @Column(name = "package_sessions")
    private Integer packageSessions;

    /** What the whole package costs. */
    @Column(name = "package_price", precision = 14, scale = 2)
    private BigDecimal packagePrice;

    /** How many days a package lasts after it is bought; null means it does not run out. */
    @Column(name = "package_valid_days")
    private Integer packageValidDays;

    /**
     * Products used up each time this service is done: a JSON list of
     * {@code {"itemId": "...", "quantity": 0.5}}. Null when none is recorded.
     */
    @Column(name = "consumables_json", length = 4000)
    private String consumablesJson;

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
