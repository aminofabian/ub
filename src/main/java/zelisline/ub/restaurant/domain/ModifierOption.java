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

/** One choice in a modifier group, with what it adds to the price. */
@Getter
@Setter
@Entity
@Table(name = "modifier_options")
public class ModifierOption {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "group_id", nullable = false, length = 36)
    private String groupId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "price_delta", nullable = false, precision = 14, scale = 2)
    private BigDecimal priceDelta = BigDecimal.ZERO;

    @Column(name = "is_default", nullable = false)
    private boolean defaultOption;

    @Column(name = "available", nullable = false)
    private boolean available = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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
