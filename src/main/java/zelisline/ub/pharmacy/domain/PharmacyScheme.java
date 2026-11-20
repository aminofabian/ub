package zelisline.ub.pharmacy.domain;

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

/** A medical scheme or insurer the shop deals with. Kiosk holds no tariff and sends it nothing. */
@Getter
@Setter
@Entity
@Table(name = "pharmacy_schemes")
public class PharmacyScheme {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "name", nullable = false, length = 191)
    private String name;

    @Column(name = "contact", length = 255)
    private String contact;

    @Column(name = "payment_terms_days")
    private Integer paymentTermsDays;

    /**
     * The customer record that stands for this scheme. The scheme's share of a sale goes on that
     * customer's tab, so the existing credit ledger tracks what the scheme owes.
     */
    @Column(name = "credit_customer_id", length = 36)
    private String creditCustomerId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
