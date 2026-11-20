package zelisline.ub.pharmacy.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One prescription dispensed in one sale (ADR 0033). Append-only: a correction is a new row that
 * points at the old one, and a voided sale keeps its record with {@link #voidedAt} set.
 */
@Getter
@Setter
@Entity
@Table(name = "dispensing_records")
public class DispensingRecord {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "sale_id", nullable = false, length = 36)
    private String saleId;

    /** The user holding pharmacy.dispense who supplied the medicines. */
    @Column(name = "dispensed_by", nullable = false, length = 36)
    private String dispensedBy;

    /** The user who rang the sale; differs from the dispenser when a pharmacist approved by PIN. */
    @Column(name = "sold_by", length = 36)
    private String soldBy;

    @Column(name = "approval_id", length = 36)
    private String approvalId;

    @Column(name = "prescriber_name", nullable = false, length = 191)
    private String prescriberName;

    @Column(name = "prescriber_reg_no", length = 64)
    private String prescriberRegNo;

    @Column(name = "prescription_ref", length = 64)
    private String prescriptionRef;

    @Column(name = "prescription_date")
    private LocalDate prescriptionDate;

    /** Optional. Health-related personal data: never log or export by default. */
    @Column(name = "patient_name", length = 191)
    private String patientName;

    /** Optional. Health-related personal data: never log or export by default. */
    @Column(name = "patient_phone", length = 32)
    private String patientPhone;

    /** The record this one corrects, if any. Records are never edited in place. */
    /** The patient profile this record belongs to, when one was chosen (Phase 2). */
    @Column(name = "patient_id", length = 36)
    private String patientId;

    @Column(name = "supersedes_id", length = 36)
    private String supersedesId;

    /** The second person who checked this record, when the shop uses a second check. */
    @Column(name = "verified_by", length = 36)
    private String verifiedBy;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by", length = 36)
    private String voidedBy;

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
