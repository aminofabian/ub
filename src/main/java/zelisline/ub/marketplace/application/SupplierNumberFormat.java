package zelisline.ub.marketplace.application;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SupplierNumberFormat {

    /** Canonical display is S-0001 … S-9999 (then S-10000+ without forcing width). */
    private static final Pattern PATTERN = Pattern.compile("^S-?0*([1-9]\\d*)$", Pattern.CASE_INSENSITIVE);
    private static final int PAD_WIDTH = 4;

    private SupplierNumberFormat() {
    }

    public static String format(long sequence) {
        if (sequence < 1L) {
            throw new IllegalArgumentException("Supplier sequence must be >= 1");
        }
        if (sequence < 10_000L) {
            return "S-" + String.format(Locale.ROOT, "%0" + PAD_WIDTH + "d", sequence);
        }
        return "S-" + sequence;
    }

    /** Normalize user input to canonical S-0001 form, or null if blank/invalid. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        Matcher m = PATTERN.matcher(trimmed);
        if (!m.matches()) {
            if (trimmed.chars().allMatch(Character::isDigit)) {
                try {
                    long n = Long.parseLong(trimmed);
                    return n >= 1L ? format(n) : null;
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            return null;
        }
        try {
            long n = Long.parseLong(m.group(1));
            return format(n);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** True when input is clearly a supplier number (S-#### or 1–4 digits), not a name/phone. */
    public static boolean looksLikeSupplierNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String trimmed = raw.trim().replace(" ", "");
        if (PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        return trimmed.chars().allMatch(Character::isDigit)
                && trimmed.length() >= 1
                && trimmed.length() <= 4;
    }
}
