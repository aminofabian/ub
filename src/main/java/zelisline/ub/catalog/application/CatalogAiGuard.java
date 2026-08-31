package zelisline.ub.catalog.application;

import java.util.regex.Pattern;

/**
 * Stops the catalog AI filing a product into the wrong aisle when the name
 * already says what it is (Kabras sugar is sugar, not cereals).
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

    private static final Pattern CEREAL_AISLE =
            Pattern.compile("\\b(cereal|cereals|breakfast)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CEREAL_EVIDENCE =
            Pattern.compile(
                    "\\b(cereal|cereals|flake|flakes|weetabix|oats|oat|porridge|maize|unga|wheat|sorghum|millet)\\b",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern SUGAR =
            Pattern.compile("\\b(sugar|sukari|sugarcane)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SALT =
            Pattern.compile("\\b(salt|chumvi)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEA =
            Pattern.compile("\\b(tea|chai|teabag|teabags)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RICE =
            Pattern.compile("\\b(rice|mchele)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLOUR =
            Pattern.compile("\\b(flour|unga)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DRY_GROCERY =
            Pattern.compile(
                    "\\b(sugar|sukari|sugarcane|salt|chumvi|tea|chai|teabag|teabags|rice|mchele|flour|unga|maize|beans|biscuit|biscuits|cookie|cookies|snack|snacks)\\b",
                    Pattern.CASE_INSENSITIVE);

    private CatalogAiGuard() {}

    static boolean allows(String aisleName, String productName, String brand) {
        if (aisleName == null || aisleName.isBlank()) {
            return false;
        }
        String haystack = haystack(productName, brand);
        if (BABY_AISLE.matcher(aisleName).find()) {
            return BABY_EVIDENCE.matcher(haystack).find();
        }
        if (PHARMACY_AISLE.matcher(aisleName).find()) {
            return PHARMACY_EVIDENCE.matcher(haystack).find();
        }
        if (VITAMIN_AISLE.matcher(aisleName).find()) {
            return VITAMIN_EVIDENCE.matcher(haystack).find();
        }
        if (CEREAL_AISLE.matcher(aisleName).find()) {
            // Cereals is for flakes, maize, wheat, oats, Weetabix. A dry grocery staple
            // whose name has no cereal evidence (sugar, salt, tea, rice, flour, biscuits)
            // never belongs there.
            return !(isDryGroceryStaple(productName, brand)
                    && !CEREAL_EVIDENCE.matcher(haystack).find());
        }
        return true;
    }

    /** Sugar, tea, rice, flour and similar staples may live in Grocery. */
    static boolean isDryGroceryStaple(String productName, String brand) {
        return DRY_GROCERY.matcher(haystack(productName, brand)).find();
    }

    /**
     * Tight shelf name taken from the product itself, e.g. "Kabras sugar" → Sugar,
     * "Lipton tea bags" → Tea. Returns null when the name does not clearly name a
     * shelf (maize flour stays in Cereals, Omo has no named shelf).
     */
    static String namedShelf(String productName, String brand) {
        String haystack = haystack(productName, brand);
        boolean cerealEvidence = CEREAL_EVIDENCE.matcher(haystack).find();
        if (SUGAR.matcher(haystack).find() && !cerealEvidence) {
            return "Sugar";
        }
        if (SALT.matcher(haystack).find() && !cerealEvidence) {
            return "Salt";
        }
        if (TEA.matcher(haystack).find() && !cerealEvidence) {
            return "Tea";
        }
        if (RICE.matcher(haystack).find() && !cerealEvidence) {
            return "Rice";
        }
        if (FLOUR.matcher(haystack).find() && !cerealEvidence) {
            return "Flour";
        }
        return null;
    }

    private static String haystack(String productName, String brand) {
        return ((productName == null ? "" : productName) + " " + (brand == null ? "" : brand)).trim();
    }
}
