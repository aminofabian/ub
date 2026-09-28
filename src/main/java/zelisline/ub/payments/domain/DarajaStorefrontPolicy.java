package zelisline.ub.payments.domain;

/**
 * Daraja (BYO shortcode, or Lipa Na M-Pesa while the custody rail is Daraja)
 * is live on the till as soon as the method is active. The public shop needs
 * a merchant request and a Super Admin approval before the same method is offered.
 */
public final class DarajaStorefrontPolicy {

    public static final String OFF = "OFF";
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    public static final String SHOP_BLOCKED_MESSAGE =
            "Daraja is on at the till. Cash stays the default there. "
                    + "The online shop stays off until Kiosk approves it.";

    private DarajaStorefrontPolicy() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return OFF;
        }
        String value = raw.trim().toUpperCase();
        return switch (value) {
            case PENDING, APPROVED, REJECTED, OFF -> value;
            default -> OFF;
        };
    }

    public static boolean isApproved(String raw) {
        return APPROVED.equals(normalize(raw));
    }

    public static boolean requiresApproval(GatewayType type, String custodyProvider) {
        if (type == GatewayType.DARAJA) {
            return true;
        }
        return type == GatewayType.CUSTODY_MPESA
                && PlatformMpesaCustodyProviders.DARAJA.equals(custodyProvider);
    }
}
