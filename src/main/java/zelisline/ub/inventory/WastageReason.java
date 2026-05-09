package zelisline.ub.inventory;

/**
 * Standardised categories for inventory wastage / shrinkage.
 * Maps to {@code stock_movements.reason} values.
 *
 * <p>These replace free-text reasons so reports can group wastage
 * by category (spoilage vs theft vs breakage etc.).</p>
 */
public enum WastageReason {

    /** Natural decay — produce, dairy, baked goods past usable condition. */
    SPOILAGE,

    /** Physical damage — broken eggs, crushed packaging, dropped items. */
    BREAKAGE,

    /** Confirmed or suspected theft / pilferage. */
    THEFT,

    /** Items taken for quality testing, demo, or sampling. */
    SAMPLE,

    /** Owner / staff personal consumption written off. */
    PERSONAL_USE,

    /** Batch exceeded its expiry date and was written off. */
    EXPIRED,

    /** Discrepancy found during stock-take (counting error). */
    COUNTING_ERROR,

    /** Anything that doesn't fit the categories above. */
    OTHER;

    /**
     * Returns the enum constant matching {@code input}, case-insensitively,
     * trimming whitespace, or {@code OTHER} if no match.
     */
    public static WastageReason fromString(String input) {
        if (input == null || input.isBlank()) {
            return OTHER;
        }
        String cleaned = input.strip().toUpperCase().replace(' ', '_');
        for (WastageReason r : values()) {
            if (r.name().equals(cleaned)) {
                return r;
            }
        }
        return OTHER;
    }
}
