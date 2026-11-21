package zelisline.ub.clinic.domain;

import java.time.Instant;

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
 * A business that has switched Clinic on (ADR 0034). Backend clinic rules key off this row and
 * off permissions, never off the shop format.
 */
@Getter
@Setter
@Entity
@Table(name = "clinic_settings")
public class ClinicSettings {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Take the consultation fee when the visit is opened; the balance at the end. */
    @Column(name = "fee_first", nullable = false)
    private boolean feeFirst;

    /** Days after a visit in which a review visit is free; 0 switches review visits off. */
    @Column(name = "free_review_days", nullable = false)
    private int freeReviewDays;

    /** The service a visit opens with when reception names none. */
    @Column(name = "consultation_service_id", length = 36)
    private String consultationServiceId;

    /** The (normally free) service a review visit opens with. */
    @Column(name = "review_service_id", length = 36)
    private String reviewServiceId;

    /** Comma-separated ids of services that are tests or procedures (the lab and treatment station). */
    @Column(name = "perform_service_ids", length = 4000)
    private String performServiceIds;

    /** Whoever wrote a prescription may not also take payment for its prescription-only medicines. */
    @Column(name = "second_person_for_gated", nullable = false)
    private boolean secondPersonForGated;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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
