package zelisline.ub.catalog.application;

import java.util.Locale;
import java.util.Set;

/**
 * Mirrors the frontend {@code joinProductNameParts} helper so API payloads read the same way the
 * catalogue does: a family and an option label become one flowing title, e.g. "Velvex Products" plus
 * "Scouring Powder Lavender Fragrance 1Kg" reads as "Velvex Scouring Powder Lavender Fragrance 1Kg".
 */
public final class ProductDisplayName {

    /** Collective words that read as noise once a family name is folded into a product title. */
    private static final Set<String> FAMILY_FILLER_WORDS =
            Set.of("product", "products", "brand", "brands", "range", "collection");

    private ProductDisplayName() {
    }

    /** Joins family and option, dropping whichever part already contains the other. */
    public static String join(String family, String option) {
        String opt = normalize(option);
        String fam = trimFamilyFiller(normalize(family));
        if (fam.isEmpty()) {
            return opt;
        }
        if (opt.isEmpty()) {
            return fam;
        }
        String famLower = fam.toLowerCase(Locale.ROOT);
        String optLower = opt.toLowerCase(Locale.ROOT);
        if (containsPhrase(optLower, famLower)) {
            return opt;
        }
        if (containsPhrase(famLower, optLower)) {
            return fam;
        }
        return fam + " " + opt;
    }

    /** Appends a code (SKU / barcode) that identifies a row but isn't part of the product's name. */
    public static String withCode(String name, String code) {
        String base = normalize(name);
        String suffix = normalize(code);
        if (suffix.isEmpty()) {
            return base;
        }
        return base.isEmpty() ? suffix : base + " (" + suffix + ")";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private static String trimFamilyFiller(String family) {
        int lastSpace = family.lastIndexOf(' ');
        if (lastSpace < 0) {
            return family;
        }
        String last = family.substring(lastSpace + 1).toLowerCase(Locale.ROOT);
        return FAMILY_FILLER_WORDS.contains(last) ? family.substring(0, lastSpace) : family;
    }

    private static boolean containsPhrase(String haystack, String needle) {
        return haystack.equals(needle)
                || haystack.startsWith(needle + " ")
                || haystack.endsWith(" " + needle)
                || haystack.contains(" " + needle + " ");
    }
}
