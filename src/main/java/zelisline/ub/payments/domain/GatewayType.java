package zelisline.ub.payments.domain;

/**
 * Identifies a payment gateway implementation.
 *
 * <p>Stored as a {@code VARCHAR(32)} column via {@code @Enumerated(EnumType.STRING)}.
 * The {@code MANUAL} type is a special case — it is always available to every
 * tenant and does not appear in the {@code platform_payment_gateways} registry.
 */
public enum GatewayType {

    KOPOKOPO,
    PAYSTACK,
    DARAJA,
    PESAPAL,
    MANUAL;

    public String wire() {
        return name().toLowerCase();
    }

    public static GatewayType fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("gatewayType must not be blank");
        }
        return GatewayType.valueOf(value.trim().toUpperCase());
    }
}
