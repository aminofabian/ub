package zelisline.ub.catalog.application;

import java.util.List;
import java.util.Locale;

/**
 * High-confidence Kenyan shop-floor homes for brands that models routinely
 * dump into Grocery or the wrong fat/oil bay.
 */
final class KenyanShelfHints {

    record ShelfHint(List<String> departments, List<String> categories, List<String> avoid) {
        String preferredDepartment() {
            return departments.get(0);
        }

        String preferredCategory() {
            return categories.get(0);
        }

        boolean avoids(String name) {
            if (name == null || name.isBlank()) {
                return false;
            }
            String lower = name.trim().toLowerCase(Locale.ROOT);
            for (String banned : avoid) {
                if (lower.equals(banned.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final List<Entry> ENTRIES = List.of(
            entry(List.of("blue band", "blueband"),
                    List.of("Dairy", "Spreads"),
                    List.of("Margarine", "Spreads", "Butter & margarine"),
                    List.of("Grocery", "Goods", "Cooking fat", "Cooking oil", "Oils & fats")),
            entry(List.of("flora", "ramia"),
                    List.of("Dairy", "Spreads"),
                    List.of("Margarine", "Spreads"),
                    List.of("Grocery", "Goods", "Cooking fat")),
            entry(List.of("kimbo", "cowboy", "kasuku"),
                    List.of("Oils & fats", "Grocery"),
                    List.of("Cooking fat", "Cooking fats"),
                    List.of("Dairy", "Margarine", "Spreads")),
            entry(List.of("elianto", "salit", "golden fry", "ufuta"),
                    List.of("Oils & fats", "Grocery"),
                    List.of("Cooking oil", "Edible oil"),
                    List.of("Dairy", "Margarine", "Cooking fat")));

    private record Entry(List<String> needles, ShelfHint hint) {}

    private KenyanShelfHints() {}

    static ShelfHint match(String name, String brand) {
        String haystack = normalize((name == null ? "" : name) + " " + (brand == null ? "" : brand));
        if (haystack.isBlank()) {
            return null;
        }
        for (Entry entry : ENTRIES) {
            for (String needle : entry.needles()) {
                if (haystack.contains(needle)) {
                    return entry.hint();
                }
            }
        }
        return null;
    }

    static boolean isCatchAll(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return lower.equals("grocery")
                || lower.equals("goods")
                || lower.equals("general")
                || lower.equals("general shop")
                || lower.equals("retail")
                || lower.equals("retail shop")
                || lower.equals("other")
                || lower.equals("misc")
                || lower.equals("miscellaneous")
                || lower.equals("default")
                || lower.equals("uncategorized")
                || lower.equals("uncategorised");
    }

    private static Entry entry(
            List<String> needles, List<String> departments, List<String> categories, List<String> avoid) {
        return new Entry(needles, new ShelfHint(departments, categories, avoid));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
