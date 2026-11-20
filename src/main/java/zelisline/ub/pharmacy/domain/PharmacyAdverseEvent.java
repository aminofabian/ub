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
 * A note on a suspected adverse reaction or a quality complaint. The description is kept as
 * written; only the "reported" fields change afterwards.
 */
@Getter
@Setter
@Entity
@Table(name = "pharmacy_adverse_events")
public class PharmacyAdverseEvent {

    public static final String SEVERITY_MILD = "MILD";
    public static final String SEVERITY_MODERATE = "MODERATE";
    public static final String SEVERITY_SEVERE = "SEVERE";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "occurred_on", nullable = false)
    private LocalDate occurredOn;

    @Column(name = "item_id", length = 36)
    private String itemId;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(name = "lot_number", length = 64)
    private String lotNumber;

    @Column(name = "patient_id", length = 36)
    private String patientId;

    @Column(name = "description", nullable = false, length = 2000)
    private String description;

    @Column(name = "severity", nullable = false, length = 16)
    private String severity;

    @Column(name = "action_taken", length = 500)
    private String actionTaken;

    @Column(name = "reported_externally", nullable = false)
    private boolean reportedExternally;

    @Column(name = "external_ref", length = 64)
    private String externalRef;

    @Column(name = "reported_at")
    private Instant reportedAt;

    @Column(name = "recorded_by", nullable = false, length = 36)
    private String recordedBy;

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
