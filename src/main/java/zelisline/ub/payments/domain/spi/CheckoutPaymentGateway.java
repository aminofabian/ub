package zelisline.ub.payments.domain.spi;

/**
 * A {@link PaymentGateway} that also supports provider-hosted checkout
 * (initialize → redirect shopper → verify server-side).
 *
 * <p>Kept as a sub-interface so STK/till-only gateways (KopoKopo, Manual) are
 * untouched. Callers resolve via {@code PaymentGatewayRegistry} and narrow
 * with {@code instanceof CheckoutPaymentGateway}.
 */
public interface CheckoutPaymentGateway extends PaymentGateway {

    /**
     * Create a provider transaction and return the hosted checkout URL.
     *
     * @param request amount (decimal), email, reference, callback, metadata, credentials
     * @return gateway-native tracking IDs and the authorization URL
     */
    CheckoutResponse initializeCheckout(CheckoutRequest request);

    /**
     * Server-side confirm of a previously initialized transaction by reference.
     *
     * @param request reference + decrypted credentials
     * @return current status from the gateway
     */
    VerifyTransactionResponse verifyTransaction(VerifyTransactionRequest request);
}
