package zelisline.ub.catalog.application;

import java.util.regex.Pattern;

/**
 * Stops the catalog AI filing a product into a specialist aisle (or inventing one)
 * unless the product name actually says it belongs there.
 *
 * <p>Example: "Nuvita" is a biscuit brand. "vita" is not evidence for Baby Care.
 */
final class CatalogAiGuard {

    private static final Pattern BABY_AISLE =
            Pattern.compile("\\b(baby|infant|toddler|maternity|nappy|nappies|diaper|diapers)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BABY_EVIDENCE =
            Pattern.compile(
                    "\\b(baby|infant|toddler|newborn|nappy|nappies|diaper|diapers|formula|wipes|teething|maternity|pampers|huggies)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern PHARMACY_AISLE =
            Pattern.compile("\\b(pharmacy|chemist|medicine|medical|clinic|hospital|prescription)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHARMACY_EVIDENCE =
            Pattern.compile(
                    "\\b(pharmacy|chemist|medicine|tablet|tablets|capsule|capsules|syrup|antiseptic)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern VITAMIN_AISLE =
            Pattern.compile("\\b(vitamin|vitamins|supplement|supplements)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern VITAMIN_EVIDENCE =
            Pattern.compile("\\b(vitamin|vitamins|supplement|supplements|multivitamin|omega)\\b", Pattern.CASE_INSENSITIVE);

    private CatalogAiGuard() {}

    static boolean allows(String aisleName, String productName, String brand) {
        if (aisleName == null || aisleName.isBlank()) {
            return false;
        }
        String haystack = ((productName == null ? "" : productName) + " " + (brand == null ? "" : brand)).trim();
        if (BABY_AISLE.matcher(aisleName).find()) {
            return BABY_EVIDENCE.matcher(haystack).find();
        }
        if (PHARMACY_AISLE.matcher(aisleName).find()) {
            return PHARMACY_EVIDENCE.matcher(haystack).find();
        }
        if (VITAMIN_AISLE.matcher(aisleName).find()) {
            return VITAMIN_EVIDENCE.matcher(haystack).find();
        }
        return true;
    }
}
