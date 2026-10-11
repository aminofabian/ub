package zelisline.ub.storefront.api.dto;

/**
 * Result of a merchant-initiated M-Pesa prompt for a web order.
 * A successful PIN settles the order because the push is registered as {@code WEB_ORDER}.
 */
public record WebOrderStkPushResponse(
        boolean accepted,
        String checkoutRequestId,
        String message
) {
}
