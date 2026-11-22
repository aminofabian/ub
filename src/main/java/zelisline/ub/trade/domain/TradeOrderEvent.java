package zelisline.ub.trade.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/** Something that happened to a trade order, numbered in order. It names a side, never a person. */
@Getter
@Setter
@Entity
@Table(
        name = "trade_order_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_trade_order_events_no", columnNames = {"trade_order_id", "event_no"}))
public class TradeOrderEvent {

    public static final String SENT = "sent";
    public static final String CONFIRMED = "confirmed";
    public static final String DECLINED = "declined";
    public static final String DISPATCHED = "dispatched";
    public static final String CANCELLED = "cancelled";
    public static final String PURCHASE_ORDER = "purchase_order";
    public static final String INVOICED = "invoiced";
    public static final String PAYMENT = "payment";
    public static final String RECEIVED = "received";
    public static final String SHORTAGE = "shortage";
    public static final String RESOLVED = "resolved";
    public static final String CREDIT_NOTE = "credit_note";

    public static final String SIDE_SUPPLIER = "supplier";
    public static final String SIDE_BUYER = "buyer";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "trade_order_id", nullable = false, length = 36)
    private String tradeOrderId;

    @Column(name = "event_no", nullable = false)
    private int eventNo;

    @Column(name = "kind", nullable = false, length = 32)
    private String kind;

    @Column(name = "actor_side", nullable = false, length = 16)
    private String actorSide;

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
