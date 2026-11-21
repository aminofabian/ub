package zelisline.ub.pharmacy.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

/**
 * A patient profile kept by a pharmacy (ADR 0033, Phase 2). Health data: read only with
 * {@code pharmacy.records.read}, and every read is audited. Never logged.
 */
@Getter
@Setter
@Entity
@Table(name = "pharmacy_patients")
public class PharmacyPatient {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** Running number per business, shown as "P-0412". Never reused, even after removal. */
    @Column(name = "patient_no")
    private Long patientNo;

    @Column(name = "name", nullable = false, length = 191)
    private String name;

    @Column(name = "phone", length = 32)
    private String phone;

    /** Who usually pays for this patient ({@code customers.id}); a parent for a child. Optional. */
    @Column(name = "customer_id", length = 36)
    private String customerId;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    /** As told to the pharmacy. Shown to the dispenser; Kiosk does not check prescriptions against it. */
    @Column(name = "allergies", length = 500)
    private String allergies;

    @Column(name = "notes", length = 1000)
    private String notes;

    /** The patient agreed to be reminded about refills. */
    @Column(name = "reminder_consent", nullable = false)
    private boolean reminderConsent;

    @Column(name = "reminder_consent_at")
    private Instant reminderConsentAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;

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
