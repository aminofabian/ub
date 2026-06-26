package zelisline.ub.globalcatalog.application;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Derives comparable keys from catalog product labels so tenant items and global
 * products match despite casing, punctuation, spacing, and unit formatting differences.
 */
final class CatalogProductMatchNormalizer {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern TRAILING_SIZE = Pattern.compile(
            "(?i)\\s*[-–/]?\\s*\\d+(?:[.,]\\d+)?\\s*(?:ml|millilitre?s?|l|litre?s?|liter?s?|g|gm|gram?s?|kg|kilogram?s?|pcs?|pack)\\s*$"
    );
    private static final Pattern UNIT_TOKENS = Pattern.compile(
            "(?i)(?<![a-z])(millilitre?s?|liter?s?|litre?s?|kilogram?s?|gram?s?|gm|kg|ml|pcs?|pack)(?![a-z])"
    );

    private CatalogProductMatchNormalizer() {
    }

    record ParsedLabels(String displayName, String brand, String size) {
    }

    static ParsedLabels parse(String name, String brand, String size, String variantName) {
        String displayName = blankToNull(name);
        String parsedBrand = blankToNull(brand);
        String parsedSize = blankToNull(size);
        if (parsedSize == null) {
            parsedSize = blankToNull(variantName);
        }

        if (displayName != null && displayName.contains(" - ")) {
            String[] parts = displayName.split(" - ", 2);
            if (parsedBrand == null) {
                parsedBrand = parts[0].trim();
            }
            if (parsedSize == null) {
                parsedSize = parts[1].trim();
            }
        }

        if (displayName == null) {
            displayName = joinNonBlank(parsedBrand, parsedSize);
        }

        return new ParsedLabels(displayName, parsedBrand, parsedSize);
    }

    static Set<String> matchKeys(String name, String brand, String size, String variantName) {
        ParsedLabels labels = parse(name, brand, size, variantName);
        Set<String> keys = new LinkedHashSet<>();

        addNameKeys(keys, labels.displayName());
        addNameKeys(keys, joinNonBlank(labels.brand(), labels.size()));

        String baseName = baseNameBeforeDash(labels.displayName());
        if (baseName != null && !baseName.isBlank()) {
            addNameKeys(keys, baseName);
            String baseWithoutSize = stripTrailingSize(baseName);
            if (baseWithoutSize != null && !baseWithoutSize.isBlank()) {
                addNameKeys(keys, baseWithoutSize);
            }
        }

        if (labels.brand() != null && labels.size() != null) {
            String brandSizeKey = compact(labels.brand()) + "|" + compactSize(labels.size());
            if (brandSizeKey.length() >= 5) {
                keys.add("brand-size:" + brandSizeKey);
            }
        }

        if (baseName != null && labels.size() != null) {
            String baseSizeKey = compact(baseName) + "|" + compactSize(labels.size());
            if (baseSizeKey.length() >= 6) {
                keys.add("base-size:" + baseSizeKey);
            }
        }

        return keys.stream()
                .filter(key -> key != null && !key.isBlank())
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
    }

    private static void addNameKeys(Set<String> keys, String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return;
        }

        String normalized = normalizeToken(rawName);
        if (normalized.length() >= 4) {
            keys.add("name:" + normalized);
        }

        String fuzzy = fuzzy(rawName);
        if (fuzzy.length() >= 6) {
            keys.add("fuzzy:" + fuzzy);
        }

        String compact = compact(rawName);
        if (compact.length() >= 5) {
            keys.add("compact:" + compact);
        }
    }

    static String normalizeToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static String fuzzy(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String lowered = value.toLowerCase(Locale.ROOT)
                .replace('&', ' ')
                .replace('/', ' ')
                .replace('-', ' ')
                .replace('_', ' ')
                .replace('.', ' ');
        lowered = UNIT_TOKENS.matcher(lowered).replaceAll(match -> canonicalUnit(match.group(1)));
        return lowered.replaceAll("\\s+", " ").trim();
    }

    static String compact(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String fuzzy = fuzzy(value);
        return NON_ALPHANUMERIC.matcher(fuzzy).replaceAll("");
    }

    static String compactSize(String value) {
        return compact(value);
    }

    private static String canonicalUnit(String unit) {
        String token = unit.toLowerCase(Locale.ROOT);
        if (token.startsWith("millilitre") || token.equals("ml")) {
            return "ml";
        }
        if (token.startsWith("litre") || token.startsWith("liter") || token.equals("l")) {
            return "l";
        }
        if (token.startsWith("kilogram") || token.equals("kg")) {
            return "kg";
        }
        if (token.startsWith("gram") || token.equals("gm") || token.equals("g")) {
            return "g";
        }
        if (token.startsWith("pc") || token.equals("pack")) {
            return "pc";
        }
        return token;
    }

    private static String baseNameBeforeDash(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        int dash = name.indexOf(" - ");
        if (dash < 0) {
            return name.trim();
        }
        return name.substring(0, dash).trim();
    }

    private static String stripTrailingSize(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String stripped = TRAILING_SIZE.matcher(name).replaceAll("").trim();
        return stripped.isBlank() ? null : stripped;
    }

    private static String joinNonBlank(String left, String right) {
        if (left == null || left.isBlank()) {
            return right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        return left.trim() + " " + right.trim();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
