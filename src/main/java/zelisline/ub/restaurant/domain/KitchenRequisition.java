package zelisline.ub.restaurant.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The kitchen asking the store for ingredients (ADR 0020, amendment 4). It is a record of who
 * asked for what and what they were given, not a stock movement: a branch has one stock count,
 * and an ingredient leaves it when the dish is sold.
 */
@Getter
@Setter
@Entity
@Table(name = "kitchen_requisitions")
public class KitchenRequisition {

    public static final String REQUESTED = "REQUESTED";
    public static final String ISSUED = "ISSUED";
    public static final String REFUSED = "REFUSED";
    public static final String CANCELLED = "CANCELLED";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    /** The station that asked, when the kitchen has more than one. */
    @Column(name = "station_id", length = 36)
    private String stationId;

    /** Counts up within a branch; what staff say to each other. */
    @Column(name = "requisition_no", nullable = false)
    private long requisitionNo;

    @Column(name = "status", nullable = false, length = 16)
    private String status = REQUESTED;

    @Column(name = "note", length = 255)
    private String note;

    /** Made by the device that asked, so a request sent twice is one request. */
    @Column(name = "client_key", length = 64)
    private String clientKey;

    @Column(name = "requested_by", nullable = false, length = 36)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 255)
    private String decisionNote;

    @PrePersist
    void onCreate() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (requestedAt == null) {
            requestedAt = Instant.now();
        }
    }
}
