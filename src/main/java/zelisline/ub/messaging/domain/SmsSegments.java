package zelisline.ub.messaging.domain;

/**
 * Counts how many SMS parts a text is sent as. A text that fits the GSM 7-bit
 * alphabet carries 160 characters in one part or 153 per part when split; anything
 * else (emoji, most non-Latin scripts) is sent as UCS-2: 70 in one part, 67 per part.
 * One part = one SMS credit (SMS Hub decision, ADR 0013).
 */
public final class SmsSegments {

    private static final String GSM_BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ ÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
                    + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    /** Each of these costs two GSM characters (escape + character). */
    private static final String GSM_EXTENDED = "^{}\\[~]|€\f";

    private SmsSegments() {
    }

    public static boolean isGsm7(String body) {
        if (body == null) {
            return true;
        }
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (GSM_BASIC.indexOf(c) < 0 && GSM_EXTENDED.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Characters the text occupies in its encoding (extended GSM characters count twice). */
    public static int units(String body) {
        if (body == null || body.isEmpty()) {
            return 0;
        }
        if (!isGsm7(body)) {
            return body.length();
        }
        int units = 0;
        for (int i = 0; i < body.length(); i++) {
            units += GSM_EXTENDED.indexOf(body.charAt(i)) >= 0 ? 2 : 1;
        }
        return units;
    }

    /** Number of parts, never less than 1. */
    public static int count(String body) {
        int units = units(body);
        if (units == 0) {
            return 1;
        }
        boolean gsm = isGsm7(body);
        int single = gsm ? 160 : 70;
        int multi = gsm ? 153 : 67;
        if (units <= single) {
            return 1;
        }
        return (units + multi - 1) / multi;
    }
}
