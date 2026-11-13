package zelisline.ub.credits.sms.domain;

import java.util.Locale;

/**
 * Values of {@code customers.sms_marketing_status} (SMS Hub, ADR 0013). A customer who
 * has not answered is {@link #UNKNOWN}: they may get receipts and order updates, never
 * promotions.
 */
public final class SmsMarketingStatus {

    public static final String UNKNOWN = "UNKNOWN";
    public static final String OPTED_IN = "OPTED_IN";
    public static final String OPTED_OUT = "OPTED_OUT";

    public static final String SOURCE_STAFF = "STAFF";
    public static final String SOURCE_SMS_KEYWORD = "SMS_KEYWORD";

    private SmsMarketingStatus() {
    }

    /** Normalised status, or null when the value is not one of the three. */
    public static String parse(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case UNKNOWN, OPTED_IN, OPTED_OUT -> v;
            default -> null;
        };
    }

    public static boolean optedIn(String status) {
        return OPTED_IN.equals(status);
    }
}
