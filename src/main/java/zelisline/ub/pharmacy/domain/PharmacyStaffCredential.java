package zelisline.ub.pharmacy.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** A staff member's professional title and registration number, shown on dispensing records. */
@Getter
@Setter
@Entity
@Table(name = "pharmacy_staff_credentials")
public class PharmacyStaffCredential {

    @Id
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "title", length = 64)
    private String title;

    /** As entered by the shop; not verified with a regulator. */
    @Column(name = "registration_no", length = 64)
    private String registrationNo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
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
