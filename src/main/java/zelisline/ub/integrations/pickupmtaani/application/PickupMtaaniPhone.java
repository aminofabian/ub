package zelisline.ub.integrations.pickupmtaani.application;

import java.util.regex.Pattern;

/**
 * Formats a Kenyan mobile for the Pickup Mtaani create body, which rejects
 * anything that is not {@code (\+254|0)(1|7)} + 8 digits (scope §14). We emit the
 * {@code +254} form; the existing {@code StkPhoneNormalizer} emits bare
 * {@code 2547…}, which fails their regex.
 */
public final class PickupMtaaniPhone {

    private static final Pattern API = Pattern.compile("^(\\+254|0)(1|7)[0-9]{8}$");

    private PickupMtaaniPhone() {
    }

    /** @return {@code +2547XXXXXXXX} / {@code +2541XXXXXXXX}, or null when not a Kenyan mobile. */
    public static String toApiFormat(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        String candidate;
        if (digits.startsWith("254")) {
            candidate = "+" + digits;
        } else if (digits.startsWith("0")) {
            candidate = "+254" + digits.substring(1);
        } else {
            candidate = "+254" + digits;
        }
        if (candidate.length() > 13) {
            candidate = candidate.substring(0, 13);
        }
        return API.matcher(candidate).matches() ? candidate : null;
    }
}
