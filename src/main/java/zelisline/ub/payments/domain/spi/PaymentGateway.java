package zelisline.ub.payments.domain.spi;

import java.util.Map;

import zelisline.ub.payments.domain.PaymentGatewayConfig;

/**
 * Strategy interface for payment gateway implementations.
 *
 * <p>Each implementation handles a single {@code GatewayType} and is
 * discovered by the {@code PaymentGatewayRegistry} via {@link #gatewayType()}.
 *
 * <p>Implementations are stateless — per-tenant credentials are passed
 * in with each call via {@link PaymentGatewayConfig}.
 */
public interface PaymentGateway {

    /** Which {@code GatewayType} this implementation handles. */
    String gatewayType();

    /**
     * Initiate an STK Push / payment request to a customer phone.
     *
     * @param request the push request (phone, amount, reference, credential payload)
     * @return gateway-native tracking IDs and status
     */
    StkPushResponse initiateStkPush(StkPushRequest request);

    /**
     * Poll the gateway for the status of a previously initiated push.
     *
     * @param gatewayCheckoutRequestId the ID returned by {@link #initiateStkPush(StkPushRequest)}
     * @return current status from the gateway
     */
    StkStatusResponse queryStkStatus(String gatewayCheckoutRequestId);

    /**
     * Process an incoming webhook payload from the gateway.
     * The implementation must verify gateway-specific signatures.
     *
     * @param headers the HTTP headers from the webhook request
     * @param rawBody the raw request body
     * @return a normalized, gateway-agnostic result
     */
    WebhookResult processWebhook(Map<String, String> headers, String rawBody);

    /**
     * Return display-only instructions for manual payment methods.
     *
     * @param businessId the tenant
     * @return display instructions (Till number, Paybill details, bank info, etc.)
     */
    DisplayInstructions getDisplayInstructions(String businessId);

    /**
     * Test connectivity and credential validity.
     * Called when a tenant clicks "Test Connection" in the dashboard.
     *
     * <p>Implementations should make a <strong>lightweight, non-mutating</strong>
     * API call (e.g., ping an auth/token endpoint) and return success/failure.
     *
     * @param config the tenant's gateway config with decrypted credentials
     * @return validation result with success flag and optional error details
     */
    ValidationResult validateConfiguration(PaymentGatewayConfig config);
}
