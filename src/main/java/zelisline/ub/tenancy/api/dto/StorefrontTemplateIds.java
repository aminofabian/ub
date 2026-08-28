package zelisline.ub.tenancy.api.dto;

import java.util.Set;

/**
 * Allowlisted storefront theme and landing template ids (code registry, not DB).
 */
public final class StorefrontTemplateIds {

    public static final String DEFAULT_STORE_THEME = "mart";
    public static final String DEFAULT_LANDING_TEMPLATE = "coming-soon-editorial";

    public static final Set<String> STORE_THEMES = Set.of(
            "mart",
            "butcher-board",
            "boutique-shelf",
            "spirits-cellar",
            "beauty-edit",
            "scent-story",
            "oxide",
            "tint-lab",
            "milk-run",
            "carbon-desk",
            "chem-lab",
            "print-atelier",
            "blank-drop",
            "pastry-case"
    );

    public static final Set<String> LANDING_TEMPLATES = Set.of(
            "coming-soon-editorial",
            "coming-soon-shop",
            "neighborhood-board",
            "fresh-market",
            "butchery-cut",
            "minimart-hours",
            "brand-poster",
            "front-window"
    );

    private StorefrontTemplateIds() {
    }

    public static String normalizeStoreThemeId(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_STORE_THEME;
        }
        String id = raw.trim();
        return STORE_THEMES.contains(id) ? id : DEFAULT_STORE_THEME;
    }

    public static String normalizeLandingTemplateId(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LANDING_TEMPLATE;
        }
        String id = raw.trim();
        return LANDING_TEMPLATES.contains(id) ? id : DEFAULT_LANDING_TEMPLATE;
    }

    public static boolean isValidStoreThemeId(String raw) {
        return raw != null && STORE_THEMES.contains(raw.trim());
    }

    public static boolean isValidLandingTemplateId(String raw) {
        return raw != null && LANDING_TEMPLATES.contains(raw.trim());
    }
}
