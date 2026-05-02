package zelisline.ub.catalog.application;

import java.text.Normalizer;
import java.util.Locale;

final class TaxonomySlug {

    private TaxonomySlug() {
    }

    static String fromName(String name) {
        if (name == null || name.isBlank()) {
            return "category";
        }
        String s = Normalizer.normalize(name.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        s = s.replaceAll("\\p{M}", "");
        s = s.replaceAll("[^a-z0-9]+", "-");
        s = s.replaceAll("^-+|-+$", "");
        return s.isBlank() ? "category" : s;
    }

    static String normalizeItemTypeKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^a-z0-9]+", "_");
        s = s.replaceAll("^_+|_+$", "");
        return s;
    }
}
