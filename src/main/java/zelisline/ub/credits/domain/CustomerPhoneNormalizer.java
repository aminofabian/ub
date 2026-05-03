package zelisline.ub.credits.domain;

public final class CustomerPhoneNormalizer {

    public static final int MAX_PHONE_DIGITS = 24;

    private CustomerPhoneNormalizer() {
    }

    /**
     * Strips non-digits so uniqueness checks are stable across "0722…", "+254722…", etc.
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return "";
        }
        if (digits.length() > MAX_PHONE_DIGITS) {
            return digits.substring(0, MAX_PHONE_DIGITS);
        }
        return digits;
    }
}
