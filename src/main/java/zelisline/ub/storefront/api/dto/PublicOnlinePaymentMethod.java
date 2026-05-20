package zelisline.ub.storefront.api.dto;

/**
 * An online payment gateway (e.g. KopoKopo STK Push) available at storefront checkout.
 */
public record PublicOnlinePaymentMethod(
        String configId,
        String gatewayType,
        String label,
        String displayName
) {
}
