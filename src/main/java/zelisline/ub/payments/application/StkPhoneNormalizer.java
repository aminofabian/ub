package zelisline.ub.payments.application;

/**
 * Normalizes Kenyan MSISDNs for STK push storage and lookup (2547XXXXXXXX).
 */
public final class StkPhoneNormalizer {

    private StkPhoneNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String phone = raw.replaceAll("[^0-9]", "");
        if (phone.startsWith("0")) {
            phone = "254" + phone.substring(1);
        }
        if (!phone.startsWith("254")) {
            phone = "254" + phone;
        }
        return phone.length() >= 12 ? phone : null;
    }
}
