package zelisline.ub.kplc.domain;

/**
 * Kenya Power prepaid meter numbers as printed on the meter or token slip.
 * Typically 11 digits; we accept a small band around that so older / new
 * formats still save.
 */
public final class KplcMeterNumbers {

    public static final int MIN_DIGITS = 8;
    public static final int MAX_DIGITS = 13;
    public static final int MAX_SAVED_PER_CUSTOMER = 8;

    private KplcMeterNumbers() {
    }

    /** Digit-only form, or null if it is not a plausible meter number. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.length() < MIN_DIGITS || digits.length() > MAX_DIGITS) {
            return null;
        }
        return digits.toString();
    }
}
