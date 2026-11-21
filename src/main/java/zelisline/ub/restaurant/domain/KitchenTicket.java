package zelisline.ub.restaurant.domain;

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

/**
 * What one station was told to make for one order in one send. Its lines are a snapshot and are
 * never edited; a change is another ticket of type {@code VOID} (ADR 0020).
 */
@Getter
@Setter
@Entity
@Table(name = "kitchen_tickets")
public class KitchenTicket {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    /** The order: a {@code pos_drafts} row of kind {@code ORDER}. */
    @Column(name = "order_id", nullable = false, length = 36)
    private String orderId;

    /** The order number called out to the customer (the ticket number of the order). */
    @Column(name = "order_number", nullable = false)
    private long orderNumber;

    @Column(name = "sale_id", length = 36)
    private String saleId;

    /** Null is the implicit kitchen. */
    @Column(name = "station_id", length = 36)
    private String stationId;

    @Column(name = "round_no", nullable = false)
    private int roundNo = 1;

    /** {@code NEW} or {@code VOID}. */
    @Column(name = "ticket_type", nullable = false, length = 8)
    private String ticketType;

    /** {@code OPEN} until every line is ready, then {@code READY}, then {@code SERVED}; a void is {@code VOIDED}. */
    @Column(name = "status", nullable = false, length = 8)
    private String status;

    /** Where the food goes: {@code DINE_IN}, {@code TAKEAWAY}, {@code DELIVERY}, {@code COUNTER} or {@code LODGING}. */
    @Column(name = "order_channel", length = 16)
    private String orderChannel;

    /** The table as it was when this ticket was sent, so a docket can say where the plate goes. */
    @Column(name = "table_name", length = 191)
    private String tableName;

    /** The name a counter order is called by; never a phone number. */
    @Column(name = "customer_name", length = 120)
    private String customerName;

    @Column(name = "sent_by", nullable = false, length = 36)
    private String sentBy;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "served_at")
    private Instant servedAt;

    /** For a {@code VOID} ticket: when someone in the kitchen confirmed they saw it. */
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

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
