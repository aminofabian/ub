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

/**
 * What a scheme owes for one dispensing record. With the record it is health data (a member
 * number tied to medicines): read only with {@code pharmacy.records.read}; never logged.
 */
@Getter
@Setter
@Entity
@Table(name = "pharmacy_scheme_claims")
public class PharmacySchemeClaim {

    public static final String OPEN = "OPEN";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String PAID = "PAID";
    public static final String PART_PAID = "PART_PAID";
    public static final String REJECTED = "REJECTED";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "scheme_id", nullable = false, length = 36)
    private String schemeId;

    /** Null for a claim on a visit with no medicines: it then stands on the sale alone. */
    @Column(name = "record_id", length = 36)
    private String recordId;

    @Column(name = "sale_id", nullable = false, length = 36)
    private String saleId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "member_no", nullable = false, length = 64)
    private String memberNo;

    @Column(name = "authorisation_code", length = 64)
    private String authorisationCode;

    @Column(name = "amount_claimed", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountClaimed;

    @Column(name = "amount_paid", nullable = false, precision = 14, scale = 2)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Column(name = "status", nullable = false, length = 16)
    private String status = OPEN;

    @Column(name = "status_note", length = 255)
    private String statusNote;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

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
