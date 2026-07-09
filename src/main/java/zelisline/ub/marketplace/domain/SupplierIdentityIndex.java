package zelisline.ub.marketplace.domain;

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

@Getter
@Setter
@Entity
@Table(name = "supplier_identity_index")
public class SupplierIdentityIndex {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    /** tenant | marketplace */
    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "business_id", length = 36)
    private String businessId;

    @Column(name = "supplier_id", length = 36)
    private String supplierId;

    @Column(name = "marketplace_supplier_id", length = 36)
    private String marketplaceSupplierId;

    @Column(name = "name_normalized", nullable = false, length = 255)
    private String nameNormalized;

    @Column(name = "phone_normalized", length = 32)
    private String phoneNormalized;

    @Column(name = "email_normalized", length = 255)
    private String emailNormalized;

    @Column(name = "tax_id_normalized", length = 64)
    private String taxIdNormalized;

    @Column(name = "region_hint", length = 64)
    private String regionHint;

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
