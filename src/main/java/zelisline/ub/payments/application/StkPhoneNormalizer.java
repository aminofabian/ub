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
        // "+254 07xx…" keeps the trunk 0, so the digits are 2540… (13) not 2547… (12).
        phone = dropTrunkZeroAfter254(phone);
        return phone.length() >= 12 ? phone : null;
    }

    /** {@code 2540} + a 9-digit Kenyan mobile → {@code 254} + those 9 digits. */
    static String dropTrunkZeroAfter254(String digits) {
        if (digits != null && digits.matches("2540[17]\\d{8}")) {
            return "254" + digits.substring(4);
        }
        return digits;
    }
}
