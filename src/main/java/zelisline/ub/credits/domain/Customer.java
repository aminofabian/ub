package zelisline.ub.credits.domain;

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
@Table(name = "customers")
public class Customer {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "customer_no")
    private Long customerNo;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "first_name", length = 120)
    private String firstName;

    @Column(name = "last_name", length = 120)
    private String lastName;

    @Column(name = "first_name_norm", length = 120)
    private String firstNameNorm;

    @Column(name = "last_name_norm", length = 120)
    private String lastNameNorm;

    @Column(name = "origin", nullable = false, length = 32)
    private String origin = CustomerOrigins.STAFF;

    @Column(name = "mpesa_identity_key", length = 280)
    private String mpesaIdentityKey;

    @Column(name = "mpesa_name_updated_at")
    private Instant mpesaNameUpdatedAt;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "notes")
    private String notes;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** When the desktop till uploaded this customer to the cloud (null = pending). */
    @Column(name = "cloud_synced_at")
    private Instant cloudSyncedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** When set, direct identifiers were scrubbed for erasure requests (Phase 8 Slice 5). */
    @Column(name = "anonymised_at")
    private Instant anonymisedAt;

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
