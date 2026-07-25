package zelisline.ub.suppliers.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tenant-public supplier portal slug — mirrors frontend {@code supplier-slug.ts}.
 */
public final class SupplierSlug {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern MULTI_DASH = Pattern.compile("-{2,}");
    private static final Pattern UUID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
            Pattern.CASE_INSENSITIVE);

    private SupplierSlug() {
    }

    public static String slugify(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "");
        String lower = normalized.toLowerCase(Locale.ROOT)
                .replace("&", " and ");
        String dashed = NON_ALNUM.matcher(lower).replaceAll("-");
        dashed = MULTI_DASH.matcher(dashed).replaceAll("-");
        dashed = dashed.replaceAll("^-+|-+$", "");
        if (dashed.length() > 64) {
            dashed = dashed.substring(0, 64).replaceAll("-+$", "");
        }
        return dashed;
    }

    public static String canonical(String name, String code) {
        String fromName = slugify(name);
        if (!fromName.isBlank()) {
            return fromName;
        }
        String fromCode = code != null ? slugify(code) : "";
        return fromCode.isBlank() ? "supplier" : fromCode;
    }

    public static boolean matches(String supplierId, String name, String code, String segment) {
        if (segment == null || segment.isBlank()) {
            return false;
        }
        String needle = decode(segment).toLowerCase(Locale.ROOT).trim();
        if (needle.isBlank()) {
            return false;
        }
        if (UUID.matcher(needle).matches() && supplierId != null
                && supplierId.equalsIgnoreCase(needle)) {
            return true;
        }
        if (canonical(name, code).equals(needle)) {
            return true;
        }
        String codeSlug = code != null && !code.isBlank() ? slugify(code) : "";
        return !codeSlug.isBlank() && codeSlug.equals(needle);
    }

    private static String decode(String segment) {
        try {
            return java.net.URLDecoder.decode(segment.trim(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return segment.trim();
        }
    }
}
