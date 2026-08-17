package zelisline.ub.credits.domain;

/**
 * Kopokopo customer-initiated buygoods phones arrive masked, e.g. {@code +2547XXXXX123}.
 * Palmart stores the mask, a stable assigned handle (X → {@code 00000}), and a fingerprint
 * of the visible prefix+suffix used to merge payers with the same first and last name.
 */
public final class MaskedMsisdn {

    public static final String FILLER_DIGITS = "00000";

    private MaskedMsisdn() {
    }

    public static boolean isMasked(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        return compact(raw).indexOf('X') >= 0;
    }

    /**
     * Compact form with country code and X preserved: {@code 2547XXXXX123}.
     */
    public static String compact(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String compact = raw.trim().toUpperCase().replaceAll("[^0-9X]", "");
        if (compact.isEmpty()) {
            return null;
        }
        if (compact.startsWith("0")) {
            compact = "254" + compact.substring(1);
        } else if (compact.startsWith("7") && (compact.indexOf('X') >= 0 || compact.length() == 9)) {
            compact = "254" + compact;
        }
        return compact;
    }

    public static String fingerprint(String raw) {
        Parsed parsed = parse(raw);
        return parsed == null ? null : parsed.fingerprint();
    }

    /** Assigned handle: replace the X-run with {@link #FILLER_DIGITS}. */
    public static String assignedMsisdn(String raw) {
        Parsed parsed = parse(raw);
        if (parsed == null) {
            return null;
        }
        if (!parsed.masked()) {
            return parsed.compact();
        }
        return parsed.prefix() + FILLER_DIGITS + parsed.suffix();
    }

    public static String displayMasked(String raw) {
        Parsed parsed = parse(raw);
        if (parsed == null) {
            return raw == null ? "" : raw.trim();
        }
        String localPrefix = toLocalPrefix(parsed.prefix());
        if (!parsed.masked()) {
            String local = toLocal07(parsed.compact());
            return local != null ? local : parsed.compact();
        }
        int bullets = Math.max(parsed.xCount(), 1);
        return localPrefix + "•".repeat(bullets) + parsed.suffix();
    }

    public static String displayAssigned(String raw) {
        String assigned = assignedMsisdn(raw);
        String local = toLocal07(assigned);
        return local != null ? local : (assigned == null ? "" : assigned);
    }

    public static boolean fitsMask(String maskedRaw, String completedRaw) {
        Parsed mask = parse(maskedRaw);
        String completed = compact(completedRaw);
        if (mask == null || completed == null || completed.indexOf('X') >= 0) {
            return false;
        }
        if (!mask.masked()) {
            return mask.compact().equals(completed);
        }
        String expectedPrefix = mask.prefix();
        String expectedSuffix = mask.suffix();
        if (!completed.startsWith(expectedPrefix) || !completed.endsWith(expectedSuffix)) {
            return false;
        }
        int middle = completed.length() - expectedPrefix.length() - expectedSuffix.length();
        return middle == mask.xCount() && middle > 0;
    }

    /**
     * Rebuild a real MSISDN by filling the X-run with {@code digits}.
     * Returns null when the digit count does not match the mask.
     */
    public static String completeWithDigits(String maskedRaw, String digitsRaw) {
        Parsed mask = parse(maskedRaw);
        if (mask == null || !mask.masked()) {
            return compact(digitsRaw);
        }
        String digits = digitsRaw == null ? "" : digitsRaw.replaceAll("\\D", "");
        if (digits.length() != mask.xCount()) {
            return null;
        }
        return mask.prefix() + digits + mask.suffix();
    }

    public static int missingDigitCount(String maskedRaw) {
        Parsed parsed = parse(maskedRaw);
        return parsed == null || !parsed.masked() ? 0 : parsed.xCount();
    }

    public static String toLocal07(String compactOrRaw) {
        String compact = compact(compactOrRaw);
        if (compact == null || compact.indexOf('X') >= 0) {
            return null;
        }
        if (compact.startsWith("254") && compact.length() >= 12) {
            return "0" + compact.substring(3);
        }
        return null;
    }

    private static String toLocalPrefix(String prefix254) {
        if (prefix254 != null && prefix254.startsWith("254")) {
            return "0" + prefix254.substring(3);
        }
        return prefix254 == null ? "" : prefix254;
    }

    public static Parsed parse(String raw) {
        String compact = compact(raw);
        if (compact == null) {
            return null;
        }
        int xStart = compact.indexOf('X');
        if (xStart < 0) {
            // Unmasked Kenyan MSISDN: treat first 4 / last 3 as the visible mask parts
            // so a later +2547XXXXX123 webhook can merge onto 2547XXXXX123's twin.
            if (compact.startsWith("254") && compact.length() == 12) {
                String prefix = compact.substring(0, 4);
                String suffix = compact.substring(9);
                return new Parsed(compact, prefix, suffix, 5, false);
            }
            return new Parsed(compact, compact, "", 0, false);
        }
        int xEnd = xStart;
        while (xEnd < compact.length() && compact.charAt(xEnd) == 'X') {
            xEnd++;
        }
        String prefix = compact.substring(0, xStart);
        String suffix = compact.substring(xEnd);
        int xCount = xEnd - xStart;
        if (prefix.isEmpty() && suffix.isEmpty()) {
            return null;
        }
        return new Parsed(compact, prefix, suffix, xCount, true);
    }

    public record Parsed(
            String compact,
            String prefix,
            String suffix,
            int xCount,
            boolean masked
    ) {
        public String fingerprint() {
            return prefix + "|" + suffix;
        }
    }
}
