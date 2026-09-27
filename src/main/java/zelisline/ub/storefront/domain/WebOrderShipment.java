package zelisline.ub.storefront.domain;

import java.math.BigDecimal;
import java.time.Instant;

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
 * Carrier shipment mirror for a web order (scope §10). One row per order. Created
 * at checkout with {@code book_status=pending}; the upstream parcel is created
 * later. {@code carrier} keeps the shape open for a second carrier.
 */
@Getter
@Setter
@Entity
@Table(name = "web_order_shipments")
public class WebOrderShipment {

    public static final String CARRIER_PICKUP_MTAANI = "pickup_mtaani";

    public static final String BOOK_PENDING = "pending";
    public static final String BOOK_BOOKING = "booking";
    public static final String BOOK_BOOKED = "booked";
    public static final String BOOK_FAILED = "book_failed";
    public static final String BOOK_CANCEL_REQUESTED = "cancel_requested";
    public static final String BOOK_CANCELLED = "cancelled";
    /**
     * The order was voided while the parcel was still live upstream (scope §13).
     * Polling stops; the merchant cancels the parcel explicitly or in Pickup Mtaani.
     */
    public static final String BOOK_VOIDED = "voided";

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    @Column(name = "web_order_id", nullable = false, length = 36)
    private String webOrderId;

    @Column(name = "carrier", nullable = false, length = 32)
    private String carrier;

    @Column(name = "mode", nullable = false, length = 16)
    private String mode;

    @Column(name = "origin_agent_id", nullable = false)
    private Long originAgentId;

    @Column(name = "destination_agent_id")
    private Long destinationAgentId;

    @Column(name = "doorstep_destination_id")
    private Long doorstepDestinationId;

    @Column(name = "destination_label", length = 255)
    private String destinationLabel;

    @Column(name = "location_description", length = 500)
    private String locationDescription;

    @Column(name = "quoted_fee_kes", nullable = false, precision = 14, scale = 2)
    private BigDecimal quotedFeeKes;

    @Column(name = "shopper_fee_kes", nullable = false, precision = 14, scale = 2)
    private BigDecimal shopperFeeKes;

    @Column(name = "fee_mode", nullable = false, length = 24)
    private String feeMode;

    @Column(name = "package_value_kes")
    private Integer packageValueKes;

    @Column(name = "upstream_package_id")
    private Long upstreamPackageId;

    @Column(name = "track_id", length = 128)
    private String trackId;

    @Column(name = "receipt_no", length = 64)
    private String receiptNo;

    @Column(name = "payment_status", length = 64)
    private String paymentStatus;

    @Column(name = "upstream_state", length = 64)
    private String upstreamState;

    @Column(name = "last_track_description", length = 500)
    private String lastTrackDescription;

    @Column(name = "book_status", nullable = false, length = 32)
    private String bookStatus = BOOK_PENDING;

    @Column(name = "book_error", length = 1000)
    private String bookError;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "booked_at")
    private Instant bookedAt;

    @Column(name = "raw_last_payload", columnDefinition = "json")
    private String rawLastPayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Optimistic lock (scope §13). Booking, cancelling, and polling can touch one
     * shipment at once. The mutating decision points additionally take a row lock
     * ({@code WebOrderShipmentRepository.findForUpdate}); this version is what stops
     * the unlocked poller from silently overwriting a cancel it raced.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
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
