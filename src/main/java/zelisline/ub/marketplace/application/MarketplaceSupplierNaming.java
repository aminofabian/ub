package zelisline.ub.marketplace.application;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared rules for marketplace passport display names vs shop-local names.
 */
public final class MarketplaceSupplierNaming {

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "^supplier\\s+\\d{2,8}$",
            Pattern.CASE_INSENSITIVE);

    private MarketplaceSupplierNaming() {
    }

    /** Names like "Supplier 2874" generated from phone tails. */
    public static boolean isPlaceholderName(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        return PLACEHOLDER.matcher(name.trim()).matches();
    }

    /**
     * Prefer a human shop name over a phone-derived placeholder.
     */
    public static String preferDisplayName(String preferred, String fallback) {
        String a = blankToNull(preferred);
        String b = blankToNull(fallback);
        if (a != null && !isPlaceholderName(a)) {
            return a;
        }
        if (b != null && !isPlaceholderName(b)) {
            return b;
        }
        if (a != null) {
            return a;
        }
        if (b != null) {
            return b;
        }
        return "Supplier";
    }

    public static String placeholderFromPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "Supplier";
        }
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 4) {
            return "Supplier";
        }
        return "Supplier " + digits.substring(digits.length() - 4);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    static boolean looksSameIdentity(String leftName, String rightName) {
        if (leftName == null || rightName == null) {
            return false;
        }
        return leftName.trim().toLowerCase(Locale.ROOT)
                .equals(rightName.trim().toLowerCase(Locale.ROOT));
    }
}
