package zelisline.ub.marketplace.application;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SupplierIdentityNormalizer {

    private static final Pattern LEGAL_SUFFIX = Pattern.compile(
            "\\b(ltd|limited|co|company|enterprises)\\b",
            Pattern.CASE_INSENSITIVE);

    private SupplierIdentityNormalizer() {
    }

    public static String normalizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String stripped = LEGAL_SUFFIX.matcher(raw.trim()).replaceAll("");
        return stripped.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeTaxId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "");
    }

    public static String normalizeEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /** Best-effort MSISDN normalization; full E.164 conversion deferred to StkPhoneNormalizer at call sites. */
    public static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.startsWith("0") && digits.length() == 10) {
            return "254" + digits.substring(1);
        }
        if (digits.startsWith("254")) {
            return digits;
        }
        return digits.isBlank() ? null : digits;
    }
}
