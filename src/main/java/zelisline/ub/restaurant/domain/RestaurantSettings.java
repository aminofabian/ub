package zelisline.ub.restaurant.domain;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** How one restaurant runs: its charges, how tips are shared, when its day starts. One row per business. */
@Getter
@Setter
@Entity
@Table(name = "restaurant_settings")
public class RestaurantSettings {

    @Id
    @Column(name = "business_id", nullable = false, length = 36)
    private String businessId;

    /** Added to every bill of a table's order, as a percent of its lines. Null or zero means none. */
    @Column(name = "service_charge_percent", precision = 5, scale = 2)
    private BigDecimal serviceChargePercent;

    /**
     * Who the service charge is for. {@link #CHARGE_TO_BUSINESS}: it is the restaurant's income,
     * sold as a line on the bill. {@link #CHARGE_TO_STAFF}: it is collected for the people who
     * serve and handled exactly as a tip is. How either is taxed is the accountant's to say.
     */
    @Column(name = "service_charge_to", nullable = false, length = 16)
    private String serviceChargeTo = CHARGE_TO_BUSINESS;

    public static final String CHARGE_TO_BUSINESS = "BUSINESS";
    public static final String CHARGE_TO_STAFF = "STAFF";

    public boolean serviceChargeGoesToStaff() {
        return CHARGE_TO_STAFF.equals(serviceChargeTo);
    }

    /** True: a tip is shared equally among everyone marked as serving customers. False: it goes to whoever served. */
    @Column(name = "tips_pooled", nullable = false)
    private boolean tipsPooled;

    /** Added once to a takeaway or delivery order. */
    @Column(name = "packaging_charge", precision = 14, scale = 2)
    private BigDecimal packagingCharge;

    /** The usual delivery fee; an order may carry its own. */
    @Column(name = "delivery_fee", precision = 14, scale = 2)
    private BigDecimal deliveryFee;

    /** Minutes after midnight, Nairobi time, at which a report's day starts. 240 keeps a bar's 2 am sales with the night before. */
    @Column(name = "day_starts_at_minute", nullable = false)
    private int dayStartsAtMinute;

    /** Whether a customer may add to a table's order from its QR code. */
    @Column(name = "qr_ordering", nullable = false)
    private boolean qrOrdering;

    /**
     * Whether the menu is shown on the restaurant's public web page. On unless the owner
     * switches it off: a restaurant's page without its menu is an empty page.
     */
    @Column(name = "menu_online", nullable = false)
    private boolean menuOnline = true;

    /** The business default check-in time for rooms, or null to use 14:00 (ADR 0031). */
    @Column(name = "check_in_time")
    private java.time.LocalTime checkInTime;

    /** The business default check-out time for rooms, or null to use 11:00 (ADR 0031). */
    @Column(name = "check_out_time")
    private java.time.LocalTime checkOutTime;

    /** Whether the rooms are shown and bookable on the public storefront (ADR 0031). Off by default. */
    @Column(name = "rooms_online", nullable = false)
    private boolean roomsOnline = false;

    /** Who switched QR ordering on; orders opened from a QR code are recorded as opened by them. */
    @Column(name = "qr_enabled_by", length = 36)
    private String qrEnabledBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
