package zelisline.ub.payments.domain;

/**
 * Lifecycle states for a provider-hosted checkout attempt
 * ({@code gateway_checkouts}).
 *
 * <pre>
 * PENDING   → checkout initialized, awaiting payment completion
 * SUCCESS   → provider confirmed payment (webhook or verify)
 * FAILED    → provider reported a terminal failure / declined
 * CANCELLED → abandoned or timed out (reconciliation sweep)
 * </pre>
 */
public final class GatewayCheckoutStatuses {

    public static final String PENDING = "pending";
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    private GatewayCheckoutStatuses() {
    }
}
