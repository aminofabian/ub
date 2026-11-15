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

/** One line of a kitchen ticket, as it was when it was sent. */
@Getter
@Setter
@Entity
@Table(name = "kitchen_ticket_lines")
public class KitchenTicketLine {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "ticket_id", nullable = false, length = 36)
    private String ticketId;

    /** The order line ({@code pos_draft_lines}) whose progress this line shows. */
    @Column(name = "order_line_id", nullable = false, length = 36)
    private String orderLineId;

    @Column(name = "line_index", nullable = false)
    private int lineIndex;

    @Column(name = "name", nullable = false, length = 191)
    private String name;

    @Column(name = "quantity", nullable = false, precision = 14, scale = 4)
    private BigDecimal quantity;

    /** Snapshot of the chosen modifier options (JSON). */
    @Column(name = "modifiers", length = 2000)
    private String modifiers;

    @Column(name = "note", length = 255)
    private String note;

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
