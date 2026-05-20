package zelisline.ub.storefront.api.dto;

/**
 * Optional phone override for STK Push (defaults to the order's customer phone).
 */
public record PublicWebStkPushRequest(
        String phoneNumber,
        String configId
) {
}
