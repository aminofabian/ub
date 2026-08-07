package zelisline.ub.payments.domain;

/**
 * What a {@link GatewayCheckout} attempt belongs to.
 *
 * <p>Grows with later phases: {@code PAYMENT_LINK}, {@code CREDIT_CLAIM},
 * {@code GROCERY_INVOICE}.
 */
public enum GatewayCheckoutContextType {
    /** Public storefront web order — first consumer (Phase 1). */
    WEB_ORDER
}
