package zelisline.ub.payments.domain;

/**
 * Super Admin choice for Model B (tenant till/paybill-only) platform rail.
 * Collect and settle always use the same provider — never mix Daraja and KopoKopo.
 */
public final class PlatformMpesaCustodyProviders {

    public static final String OFF = "OFF";
    public static final String KOPOKOPO = "KOPOKOPO";
    public static final String DARAJA = "DARAJA";

    private PlatformMpesaCustodyProviders() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return OFF;
        }
        String v = raw.trim().toUpperCase();
        if (KOPOKOPO.equals(v) || DARAJA.equals(v) || OFF.equals(v)) {
            return v;
        }
        throw new IllegalArgumentException("custodyProvider must be OFF, KOPOKOPO, or DARAJA");
    }
}
