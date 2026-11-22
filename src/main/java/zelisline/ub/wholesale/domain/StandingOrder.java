package zelisline.ub.wholesale.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * An order a buying shop asked to be placed again every so many days (P4). Each run places an
 * ordinary trade order, which the supplier still has to accept.
 */
@Getter
@Setter
@Entity
@Table(name = "standing_orders")
public class StandingOrder {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "supplier_business_id", nullable = false, length = 36)
    private String supplierBusinessId;

    @Column(name = "buyer_business_id", nullable = false, length = 36)
    private String buyerBusinessId;

    @Column(name = "buyer_business_name", nullable = false, length = 255)
    private String buyerBusinessName;

    @Column(name = "buyer_name", nullable = false, length = 255)
    private String buyerName;

    @Column(name = "buyer_phone", nullable = false, length = 64)
    private String buyerPhone;

    @Column(name = "every_days", nullable = false)
    private int everyDays;

    @Column(name = "next_run_on", nullable = false)
    private LocalDate nextRunOn;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_order_id", length = 36)
    private String lastOrderId;

    /** What the last run did, in a sentence. */
    @Column(name = "last_result", length = 500)
    private String lastResult;

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
