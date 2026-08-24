package zelisline.ub.catalog.application;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import zelisline.ub.catalog.domain.Item;

/**
 * Mirrors the frontend {@code joinProductNameParts} helper so API payloads read the same way the
 * catalogue does: a family and an option label become one flowing title, e.g. "Velvex Products" plus
 * "Scouring Powder Lavender Fragrance 1Kg" reads as "Velvex Scouring Powder Lavender Fragrance 1Kg".
 */
public final class ProductDisplayName {

    /** Collective words that read as noise once a family name is folded into a product title. */
    private static final Set<String> FAMILY_FILLER_WORDS =
            Set.of("product", "products", "brand", "brands", "range", "collection");

    private static final Set<String> GENERIC_OPTION_LABELS =
            Set.of("variant", "option", "variation", "default");

    private static final Pattern UUID_RE = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IMPORT_SKU_RE = Pattern.compile("^IMP-", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARCODE_MIRROR_SKU_RE =
            Pattern.compile("^BC-\\d{8,}$", Pattern.CASE_INSENSITIVE);

    private ProductDisplayName() {
    }

    /**
     * Clerk-facing shelf title: parent name plus the variant option, size, pack, or SKU
     * that tells two sibling SKUs apart. Variants that only store the parent name
     * (e.g. three "Festive Bread" rows) pick up {@code variantName} / size / pack / SKU.
     */
    public static String forItem(Item item) {
        if (item == null) {
            return "";
        }
        String family = normalize(item.getName());
        String option = descriptiveOption(item);
        if (!option.isEmpty()) {
            return join(family, option);
        }
        if (!needsSkuDisambiguation(item)) {
            return family;
        }
        String sku = humanSku(item.getSku());
        if (!sku.isEmpty() && !family.equalsIgnoreCase(sku)) {
            return withCode(family, sku);
        }
        return family;
    }

    /**
     * Distinguishing option for a two-line / split title. Empty when the row is a
     * standalone product with nothing beyond its name.
     */
    public static String optionLabel(Item item) {
        if (item == null) {
            return "";
        }
        String option = descriptiveOption(item);
        if (!option.isEmpty()) {
            return option;
        }
        if (!needsSkuDisambiguation(item)) {
            return "";
        }
        String family = normalize(item.getName());
        String sku = humanSku(item.getSku());
        if (!sku.isEmpty() && !family.equalsIgnoreCase(sku)) {
            return sku;
        }
        return "";
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

    private static String descriptiveOption(Item item) {
        String family = normalize(item.getName());
        String option = firstDistinct(family, meaningful(item.getVariantName()));
        if (option.isEmpty()) {
            option = firstDistinct(family, normalize(item.getSize()));
        }
        if (option.isEmpty()) {
            option = firstDistinct(family, packLabel(item));
        }
        if (option.isEmpty()) {
            option = firstDistinct(family, normalize(item.getBundleName()));
        }
        return option;
    }

    private static String packLabel(Item item) {
        String unit = normalize(item.getPackagingUnitName());
        BigDecimal qty = item.getPackagingUnitQty();
        if (qty != null && qty.signum() > 0) {
            String n = qty.stripTrailingZeros().toPlainString();
            if (!unit.isEmpty()) {
                return n + " " + unit;
            }
            if (item.isPackageVariant()) {
                return n + "-pack";
            }
        }
        return unit;
    }

    private static String meaningful(String value) {
        String t = normalize(value);
        if (t.isEmpty() || GENERIC_OPTION_LABELS.contains(t.toLowerCase(Locale.ROOT))) {
            return "";
        }
        return t;
    }

    private static String firstDistinct(String family, String candidate) {
        if (candidate.isEmpty()) {
            return "";
        }
        if (!family.isEmpty() && family.equalsIgnoreCase(candidate)) {
            return "";
        }
        return candidate;
    }

    private static boolean needsSkuDisambiguation(Item item) {
        String parent = item.getVariantOfItemId();
        return (parent != null && !parent.isBlank()) || item.isPackageVariant();
    }

    private static String humanSku(String sku) {
        String t = normalize(sku);
        if (t.isEmpty()) {
            return "";
        }
        if (IMPORT_SKU_RE.matcher(t).find()
                || BARCODE_MIRROR_SKU_RE.matcher(t).matches()
                || UUID_RE.matcher(t).matches()) {
            return "";
        }
        return t;
    }
}
