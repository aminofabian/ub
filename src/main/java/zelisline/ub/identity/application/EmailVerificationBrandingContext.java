package zelisline.ub.identity.application;

import java.util.Locale;
import java.util.Optional;

import zelisline.ub.tenancy.api.dto.PublicHostResolveResponse;
import zelisline.ub.tenancy.api.dto.TenantBrandingDto;
import zelisline.ub.tenancy.infrastructure.TenantHostParsing;

/**
 * Branding inputs for tenant-scoped verification emails (subdomain host, colors, logo).
 */
public record EmailVerificationBrandingContext(
        String displayName,
        String slug,
        String host,
        String primaryColor,
        String accentColor,
        String logoUrl,
        String tagline
) {

    public static EmailVerificationBrandingContext fromHost(
            Optional<PublicHostResolveResponse> tenant,
            String rawHost) {
        String host = TenantHostParsing.hostnameOnly(rawHost);
        if (host == null) {
            return platformDefault();
        }
        String slug = subdomainSlug(host);
        if (tenant.isEmpty()) {
            return defaultsForSlug(slug, host, titleCase(slug));
        }
        PublicHostResolveResponse t = tenant.get();
        TenantBrandingDto b = t.branding();
        String display = firstNonBlank(
                b.displayName(),
                t.tenantName(),
                titleCase(t.slug()),
                titleCase(slug));
        String resolvedSlug = firstNonBlank(t.slug(), slug);
        String primary = firstNonBlank(b.primaryColor(), paletteForSlug(resolvedSlug)[0]);
        String accent = firstNonBlank(b.accentColor(), paletteForSlug(resolvedSlug)[1]);
        return new EmailVerificationBrandingContext(
                display,
                resolvedSlug,
                host,
                primary,
                accent,
                blankToNull(b.logoUrl()),
                taglineForSlug(resolvedSlug));
    }

    public static EmailVerificationBrandingContext platformDefault() {
        return new EmailVerificationBrandingContext(
                "UB",
                "ub",
                null,
                "#1B4332",
                "#F59E0B",
                null,
                "Confirm your email to unlock your account.");
    }

    private static EmailVerificationBrandingContext defaultsForSlug(
            String slug,
            String host,
            String displayName) {
        String[] palette = paletteForSlug(slug);
        return new EmailVerificationBrandingContext(
                displayName,
                slug,
                host,
                palette[0],
                palette[1],
                null,
                taglineForSlug(slug));
    }

    /** First label of the host (e.g. {@code uzapoint} from {@code uzapoint.kiosk.ke}). */
    static String subdomainSlug(String host) {
        if (host == null || host.isBlank()) {
            return "ub";
        }
        int dot = host.indexOf('.');
        if (dot <= 0) {
            return host;
        }
        return host.substring(0, dot);
    }

    static String taglineForSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return "Confirm your email to get started.";
        }
        return switch (slug.toLowerCase(Locale.ROOT)) {
            case "uzapoint" -> "Your point of sale — verify once, start selling.";
            case "barakia" -> "Fresh finds await — confirm your email to get started.";
            default -> "You're one click away from your account.";
        };
    }

    /** Curated retail / POS palettes when tenant colors are unset. */
    static String[] paletteForSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return new String[] {"#0F766E", "#EA580C"};
        }
        if ("uzapoint".equalsIgnoreCase(slug)) {
            return new String[] {"#0D9488", "#EA580C"};
        }
        int idx = Math.floorMod(slug.toLowerCase(Locale.ROOT).hashCode(), PALETTES.length);
        return PALETTES[idx];
    }

    private static final String[][] PALETTES = {
            {"#0D9488", "#EA580C"},  // teal + orange (UzaPoint vibe)
            {"#1D4ED8", "#F59E0B"},  // blue + amber
            {"#7C3AED", "#10B981"},  // violet + emerald
            {"#BE123C", "#0EA5E9"},  // rose + sky
            {"#1B4332", "#52B788"},  // forest greens
    };

    private static String titleCase(String slug) {
        if (slug == null || slug.isBlank()) {
            return "Your store";
        }
        String normalized = slug.replace('-', ' ').trim();
        if (normalized.isEmpty()) {
            return "Your store";
        }
        String[] parts = normalized.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.isEmpty() ? "Your store" : sb.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.strip();
    }
}
