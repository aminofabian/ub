package zelisline.ub.marketplace.application;

import java.util.Locale;
import java.util.regex.Pattern;

public final class SupplierIdentityNormalizer {

    private static final Pattern LEGAL_SUFFIX = Pattern.compile(
            "\\b(ltd|limited|co|company|enterprises)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Kenyan national number length after country code (7XXXXXXXX). */
    public static final int PHONE_TAIL_LENGTH = 9;

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

    /**
     * Best-effort MSISDN normalization toward {@code 254…}.
     * Prefer {@link zelisline.ub.payments.application.StkPhoneNormalizer} when writing payout phones.
     */
    public static String normalizePhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return null;
        }
        if (digits.startsWith("0") && digits.length() >= 10) {
            return "254" + digits.substring(1);
        }
        if (digits.startsWith("254")) {
            return digits;
        }
        // Bare national number (9 digits) or longer without leading 0/254.
        if (digits.length() == PHONE_TAIL_LENGTH) {
            return "254" + digits;
        }
        return digits;
    }

    /**
     * Last 9 digits — the stable identity for KE mobiles across {@code +254…}, {@code 07…}, and {@code 7…}.
     */
    public static String phoneTail(String rawOrNormalized) {
        String digits = rawOrNormalized == null ? null : rawOrNormalized.replaceAll("[^0-9]", "");
        if (digits == null || digits.length() < PHONE_TAIL_LENGTH) {
            return null;
        }
        return digits.substring(digits.length() - PHONE_TAIL_LENGTH);
    }

    /** Alternate local form: {@code 2547…} ↔ {@code 07…}. */
    public static String altPhoneForm(String normalizedPhone) {
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            return normalizedPhone;
        }
        String digits = normalizedPhone.replaceAll("[^0-9]", "");
        if (digits.startsWith("254") && digits.length() == 12) {
            return "0" + digits.substring(3);
        }
        if (digits.startsWith("0") && digits.length() == 10) {
            return "254" + digits.substring(1);
        }
        return digits;
    }

    /**
     * Lookup trio for variant SQL: preferred form, alternate form, last-9 tail.
     * Returns null if the phone cannot yield a usable tail.
     */
    public static PhoneLookupForms phoneLookupForms(String raw) {
        String primary = normalizePhone(raw);
        if (primary == null) {
            return null;
        }
        String tail = phoneTail(primary);
        if (tail == null) {
            return null;
        }
        String alt = altPhoneForm(primary);
        if (alt == null || alt.isBlank()) {
            alt = primary;
        }
        return new PhoneLookupForms(primary, alt, tail);
    }

    public record PhoneLookupForms(String phone, String altPhone, String phoneTail) {
    }
}
