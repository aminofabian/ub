package zelisline.ub.posdraft.domain;

import java.math.BigDecimal;
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
import zelisline.ub.posdraft.PosDraftConstants;

@Getter
@Setter
@Entity
@Table(
        name = "pos_drafts",
        // Mirrors V372 so the test schema enforces what production does: numbers are per branch.
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uq_pos_drafts_branch_ticket",
                columnNames = {"business_id", "branch_id", "ticket_number"}))
public class PosDraft {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    @Column(name = "ticket_number", nullable = false)
    private long ticketNumber;

    @Column(name = "status", nullable = false, length = 16)
    private String status = PosDraftConstants.STATUS_PENDING;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "shift_id", length = 36)
    private String shiftId;

    @Column(name = "sale_id", length = 36)
    private String saleId;

    @Column(name = "customer_id", length = 36)
    private String customerId;

    @Column(name = "client_draft_id", length = 64)
    private String clientDraftId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "sub_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal subTotal = BigDecimal.ZERO;

    @Column(name = "discount_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "tax_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(name = "cancelled_by", length = 36)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_reason", length = 500)
    private String cancelledReason;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** {@code CART} for a held retail cart, {@code SERVICE} for a walk-in service ticket. */
    @Column(name = "draft_kind", nullable = false, length = 16)
    private String draftKind = PosDraftConstants.KIND_CART;

    /** Service tickets only: {@code WAITING}, {@code IN_SERVICE}, {@code READY_TO_PAY}, {@code ABANDONED}. */
    @Column(name = "service_stage", length = 16)
    private String serviceStage;

    /** Service tickets only: the chair (1-based) the customer is in. */
    @Column(name = "station_no")
    private Integer stationNo;

    /** Service tickets only: the staff member the customer asked for, or who took them. */
    @Column(name = "requested_staff_profile_id", length = 36)
    private String requestedStaffProfileId;

    @Column(name = "service_started_at")
    private Instant serviceStartedAt;

    @Column(name = "service_finished_at")
    private Instant serviceFinishedAt;

    /** Appointments only: when the customer is booked to start and finish. */
    @Column(name = "scheduled_start")
    private Instant scheduledStart;

    @Column(name = "scheduled_end")
    private Instant scheduledEnd;

    /** Who booked, when they are not (yet) a customer record. Customer details: never log. */
    @Column(name = "booking_name", length = 191)
    private String bookingName;

    @Column(name = "booking_phone", length = 32)
    private String bookingPhone;

    /** {@code STAFF} when taken at the counter or by phone. */
    @Column(name = "booking_source", length = 16)
    private String bookingSource;

    /** Higher is served sooner among waiting tickets; 0 is the normal queue. */
    @Column(name = "queue_priority", nullable = false)
    private int queuePriority;

    /** Whether to text the customer shortly before a booking. Costs the shop an SMS. */
    @Column(name = "send_reminder", nullable = false)
    private boolean sendReminder;

    /** Set when the reminder is claimed for sending, so it is sent at most once. */
    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    /** Money taken in advance against this ticket (ADR 0015). */
    @Column(name = "deposit_amount", precision = 14, scale = 2)
    private java.math.BigDecimal depositAmount;

    /** {@code HELD}, {@code APPLIED} (spent on the sale), {@code CREDITED} (to the customer's wallet) or {@code FORFEITED}. */
    @Column(name = "deposit_state", length = 16)
    private String depositState;

    @Column(name = "deposit_method", length = 32)
    private String depositMethod;

    /** A free repeat of work that was not right: the sale being put right. Never charged. */
    @Column(name = "redo_of_sale_id", length = 36)
    private String redoOfSaleId;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (status == null || status.isBlank()) {
            status = PosDraftConstants.STATUS_PENDING;
        }
        if (subTotal == null) {
            subTotal = BigDecimal.ZERO;
        }
        if (discountTotal == null) {
            discountTotal = BigDecimal.ZERO;
        }
        if (taxTotal == null) {
            taxTotal = BigDecimal.ZERO;
        }
        if (grandTotal == null) {
            grandTotal = BigDecimal.ZERO;
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

    public boolean isPending() {
        return PosDraftConstants.STATUS_PENDING.equals(status);
    }
}
