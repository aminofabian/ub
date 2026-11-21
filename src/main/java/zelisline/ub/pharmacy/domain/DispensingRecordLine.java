package zelisline.ub.pharmacy.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** One gated medicine on a {@link DispensingRecord}. */
@Getter
@Setter
@Entity
@Table(name = "dispensing_record_lines")
public class DispensingRecordLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "record_id", nullable = false, length = 36)
    private String recordId;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    /** Product name at the time of sale. */
    @Column(name = "item_name", nullable = false, length = 255)
    private String itemName;

    /** Class at the time of sale. */
    @Column(name = "dispensing_class", nullable = false, length = 24)
    private String dispensingClass;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    @Column(name = "instructions", length = 500)
    private String instructions;

    /** Comma-separated sale item ids; one product can span several batch rows. */
    @Column(name = "sale_item_ids", length = 1000)
    private String saleItemIds;

    /** Comma-separated manufacturer lots the units came from, for display. */
    @Column(name = "lot_numbers", length = 500)
    private String lotNumbers;

    /** What the prescription asked for, when more than was supplied (partial supply). */
    /** How much of {@link #quantity} came back on a refund; null when nothing has. */
    @Column(name = "quantity_returned", precision = 14, scale = 4)
    private BigDecimal quantityReturned;

    /** What the patient still has of this supply. */
    public BigDecimal netQuantity() {
        return quantityReturned == null ? quantity : quantity.subtract(quantityReturned).max(BigDecimal.ZERO);
    }

    public boolean anyReturned() {
        return quantityReturned != null && quantityReturned.signum() > 0;
    }

    @Column(name = "quantity_prescribed", precision = 14, scale = 4)
    private BigDecimal quantityPrescribed;

    /** When this supply runs out, for the refills-due list. */
    @Column(name = "refill_due_on")
    private java.time.LocalDate refillDueOn;

    /** The earlier line whose outstanding balance this line supplies. */
    @Column(name = "settles_line_id", length = 36)
    private String settlesLineId;

    /** Set when someone decided this line needs no more chasing: no balance to supply, no refill to remind. */
    @Column(name = "follow_up_closed_at")
    private Instant followUpClosedAt;

    @Column(name = "follow_up_closed_by", length = 36)
    private String followUpClosedBy;

    @Column(name = "follow_up_note", length = 255)
    private String followUpNote;

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
