package zelisline.ub.trade.domain;

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

/**
 * The one record two shops share about an order (ADR 0041). It belongs to neither shop's books:
 * the supplier keeps its sales order, the buyer keeps its purchase order, and this row is what
 * each may read of the other's side. Three facts are kept apart: the order, delivery and money.
 */
@Getter
@Setter
@Entity
@Table(name = "trade_orders")
public class TradeOrder {

    public static final String ORDER_PLACED = "placed";
    public static final String ORDER_CONFIRMED = "confirmed";
    public static final String ORDER_CANCELLED = "cancelled";
    public static final String ORDER_DISPUTED = "disputed";

    public static final String DELIVERY_NONE = "none";
    public static final String DELIVERY_PART = "part";
    public static final String DELIVERY_ALL = "all";

    public static final String MONEY_UNBILLED = "unbilled";
    public static final String MONEY_INVOICED = "invoiced";
    public static final String MONEY_PART_PAID = "part_paid";
    public static final String MONEY_SETTLED = "settled";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "supplier_business_id", nullable = false, length = 36)
    private String supplierBusinessId;

    @Column(name = "supplier_marketplace_id", nullable = false, length = 36)
    private String supplierMarketplaceId;

    @Column(name = "buyer_business_id", nullable = false, length = 36)
    private String buyerBusinessId;

    @Column(name = "sales_order_id", nullable = false, length = 36, unique = true)
    private String salesOrderId;

    /** The buyer's own purchase order, once the buyer has made one from this. Not set yet (P2-2). */
    @Column(name = "purchase_order_id", length = 36)
    private String purchaseOrderId;

    @Column(name = "order_code", nullable = false, length = 24)
    private String orderCode;

    @Column(name = "supplier_name", nullable = false, length = 255)
    private String supplierName;

    @Column(name = "order_state", nullable = false, length = 16)
    private String orderState = ORDER_PLACED;

    @Column(name = "delivery_state", nullable = false, length = 16)
    private String deliveryState = DELIVERY_NONE;

    @Column(name = "money_state", nullable = false, length = 16)
    private String moneyState = MONEY_UNBILLED;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "total", nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Column(name = "next_event_no", nullable = false)
    private int nextEventNo = 1;

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
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
