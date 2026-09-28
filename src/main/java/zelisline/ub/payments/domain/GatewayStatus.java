package zelisline.ub.payments.domain;

/**
 * Lifecycle states for a tenant's gateway configuration.
 *
 * <pre>
 * DRAFT   → credentials saved, not yet tested
 * TESTING → test connection in progress (transient)
 * TESTED  → test passed, ready for activation
 * ERROR   → test failed, error details stored
 * ACTIVE  → live on the till. Daraja on the public shop also needs approval.
 * </pre>
 *
 * <p>Stored as a {@code VARCHAR(16)} column in {@code payment_gateway_configs}.
 */
public enum GatewayStatus {

    DRAFT,
    TESTING,
    TESTED,
    ERROR,
    ACTIVE;

    public String wire() {
        return name().toLowerCase();
    }

    public static GatewayStatus fromWire(String value) {
        if (value == null || value.isBlank()) {
            return DRAFT;
        }
        return GatewayStatus.valueOf(value.trim().toUpperCase());
    }
}
