package zelisline.ub.restaurant.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One kitchen ticket waiting to be printed at a station. Unlike a till slip (addressed to one
 * person), a kitchen job is addressed to a branch and a station, so any signed-in device at that
 * branch may claim and print it — the one that rang the sale if it can reach the printer, otherwise
 * another. A job is claimed once (an atomic compare-and-set on {@code claimedAt}) and marked printed
 * when a device confirms the paper came out. A job still unclaimed after a while is shown on the
 * counter as "kitchen ticket not printed", so nobody assumes the kitchen saw an order it never did.
 */
@Getter
@Setter
@Entity
@Table(
        name = "kitchen_print_jobs",
        indexes = @Index(
                name = "idx_kitchen_print_pending",
                columnList = "business_id, branch_id, claimed_at, created_at"))
public class KitchenPrintJob {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "branch_id", nullable = false, length = 36)
    private String branchId;

    /** The station the ticket is for; null is the implicit single kitchen. */
    @Column(name = "station_id", length = 36)
    private String stationId;

    @Column(name = "ticket_id", nullable = false, length = 36)
    private String ticketId;

    /** The order number, kept on the job so the counter warning can name an unprinted ticket. */
    @Column(name = "order_number", nullable = false)
    private long orderNumber;

    /** {@code NEW} or {@code VOID}, from the ticket. */
    @Column(name = "ticket_type", nullable = false, length = 8)
    private String ticketType;

    /** The ticket as a device needs it to print, so a claiming device prints without another read. */
    @Column(name = "payload_json", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String payloadJson;

    /** Set when this job reprints an earlier ticket, so the slip can be marked a reprint. */
    @Column(name = "reprint_of", length = 36)
    private String reprintOf;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Set the moment a device claims the job; the compare-and-set on this makes a claim exclusive. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claimed_by", length = 36)
    private String claimedBy;

    /** Set when a device confirms the ticket printed. A claimed job with no printed time may be retried. */
    @Column(name = "printed_at")
    private Instant printedAt;

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
