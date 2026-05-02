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
@Table(name = "suppliers")
public class Supplier {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "code", length = 64)
    private String code;

    @Column(name = "supplier_type", nullable = false, length = 32)
    private String supplierType = "distributor";

    @Column(name = "vat_pin", length = 64)
    private String vatPin;

    @Column(name = "is_tax_exempt", nullable = false)
    private boolean taxExempt;

    @Column(name = "credit_terms_days")
    private Integer creditTermsDays;

    @Column(name = "credit_limit", precision = 14, scale = 2)
    private BigDecimal creditLimit;

    @Column(name = "rating", precision = 5, scale = 2)
    private BigDecimal rating;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "active";

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "payment_method_preferred", length = 32)
    private String paymentMethodPreferred;

    @Column(name = "payment_details", columnDefinition = "TEXT")
    private String paymentDetails;

    @Column(name = "prepayment_balance", nullable = false, precision = 14, scale = 2)
    private BigDecimal prepaymentBalance = BigDecimal.ZERO;

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
