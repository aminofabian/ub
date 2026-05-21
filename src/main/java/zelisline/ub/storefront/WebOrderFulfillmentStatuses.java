package zelisline.ub.storefront;

/** Pickup / fulfillment lifecycle after payment (orthogonal to {@link WebOrderStatuses}). */
public final class WebOrderFulfillmentStatuses {

    public static final String AWAITING_CONFIRMATION = "awaiting_confirmation";
    public static final String CONFIRMED = "confirmed";
    public static final String DISPATCHED = "dispatched";
    public static final String COMPLETED = "completed";

    private WebOrderFulfillmentStatuses() {
    }
}
