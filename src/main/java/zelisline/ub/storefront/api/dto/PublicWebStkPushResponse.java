package zelisline.ub.storefront.api.dto;

/**
 * Result of initiating an M-Pesa STK Push for a web order.
 */
public record PublicWebStkPushResponse(
        boolean accepted,
        String gatewayType,
        String checkoutRequestId,
        String message
) {
}
