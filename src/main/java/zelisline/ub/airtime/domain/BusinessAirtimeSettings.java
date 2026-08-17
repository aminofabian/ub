package zelisline.ub.airtime.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Per-tenant airtime opt-in and channel toggles. */
@Getter
@Setter
@Entity
@Table(name = "business_airtime_settings")
public class BusinessAirtimeSettings {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "pos_enabled", nullable = false)
    private boolean posEnabled = true;

    @Column(name = "storefront_enabled", nullable = false)
    private boolean storefrontEnabled;

    /** Tenant's own per-transaction ceiling; the platform max still applies. */
    @Column(name = "max_single_amount", precision = 14, scale = 2)
    private BigDecimal maxSingleAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        Instant now = Instant.now();
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
