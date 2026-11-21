package zelisline.ub.clinic.domain;

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
 * What a clinician prescribed on a visit (ADR 0034, decision 6). Carries no diagnosis. Health
 * data: read under the dispensing-record rule and every read is audited.
 */
@Getter
@Setter
@Entity
@Table(name = "prescriptions")
public class Prescription {

    /** Being written; the prescriber may still change it. */
    public static final String OPEN = "OPEN";
    /** Checked and put on the bill by someone who may dispense. Fixed from here. */
    public static final String PREPARED = "PREPARED";
    /** The patient is not buying the medicines here; the prescription was printed or left. */
    public static final String DECLINED = "DECLINED";
    /** Paid for and handed over; a dispensing record exists. */
    public static final String DISPENSED = "DISPENSED";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    /** The held ticket ({@code pos_drafts.id}) it was written on. */
    @Column(name = "visit_id", nullable = false, length = 36)
    private String visitId;

    @Column(name = "patient_id", nullable = false, length = 36)
    private String patientId;

    @Column(name = "prescriber_user_id", nullable = false, length = 36)
    private String prescriberUserId;

    /** Name and registration number as they stood when it was written. */
    @Column(name = "prescriber_name", nullable = false, length = 191)
    private String prescriberName;

    @Column(name = "prescriber_reg_no", length = 64)
    private String prescriberRegNo;

    @Column(name = "prescribed_on", nullable = false)
    private LocalDate prescribedOn;

    @Column(name = "status", nullable = false, length = 16)
    private String status = OPEN;

    @Column(name = "prepared_by", length = 36)
    private String preparedBy;

    @Column(name = "prepared_at")
    private Instant preparedAt;

    @Column(name = "sale_id", length = 36)
    private String saleId;

    @Column(name = "supersedes_id", length = 36)
    private String supersedesId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

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
