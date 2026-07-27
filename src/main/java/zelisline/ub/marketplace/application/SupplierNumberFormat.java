package zelisline.ub.marketplace.application;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SupplierNumberFormat {

    private static final Pattern PATTERN = Pattern.compile("^S-?0*([1-9]\\d*)$", Pattern.CASE_INSENSITIVE);
    private static final int PAD_WIDTH = 6;

    private SupplierNumberFormat() {
    }

    public static String format(long sequence) {
        if (sequence < 1L) {
            throw new IllegalArgumentException("Supplier sequence must be >= 1");
        }
        return "S-" + String.format(Locale.ROOT, "%0" + PAD_WIDTH + "d", sequence);
    }

    /** Normalize user input to canonical S-000001 form, or null if blank/invalid. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        Matcher m = PATTERN.matcher(trimmed);
        if (!m.matches()) {
            // Also accept bare digits e.g. 1 → S-000001
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
}
