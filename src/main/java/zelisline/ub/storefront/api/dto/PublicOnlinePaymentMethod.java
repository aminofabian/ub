package zelisline.ub.storefront.api.dto;

/**
 * An online payment gateway available at storefront checkout.
 *
 * @param kind {@code "stk"} for phone-prompt methods (M-Pesa STK Push),
 *             {@code "redirect"} for hosted-checkout methods (Paystack) that
 *             open an authorization URL instead of asking for a phone number.
 */
public record PublicOnlinePaymentMethod(
        String configId,
        String gatewayType,
        String label,
        String displayName,
        String kind
) {
}
