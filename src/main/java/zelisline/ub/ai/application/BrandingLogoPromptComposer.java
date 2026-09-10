package zelisline.ub.ai.application;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the merchant's short description into an Images-API prompt that
 * produces a usable shop mark (square, simple, no mockups).
 */
final class BrandingLogoPromptComposer {

    private static final int MAX_PROMPT = 1_200;
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\n\t]]");
    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private BrandingLogoPromptComposer() {}

    static String compose(
            String prompt,
            String shopName,
            String shopType,
            String primaryColor,
            String accentColor
    ) {
        String ask = clean(prompt, 600);
        String name = clean(shopName, 120);
        String type = clean(shopType, 80);
        String primary = hexOrEmpty(primaryColor);
        String accent = hexOrEmpty(accentColor);

        if (ask.isEmpty() && name.isEmpty()) {
            throw new IllegalArgumentException("Describe the logo, or set a shop name first.");
        }

        StringBuilder sb = new StringBuilder(MAX_PROMPT);
        sb.append("Professional shop logo for a Kenyan neighbourhood business.\n");
        if (!name.isEmpty()) {
            sb.append("Shop name: ").append(name).append('\n');
        }
        if (!type.isEmpty()) {
            sb.append("Kind of shop: ").append(type).append('\n');
        }
        if (!primary.isEmpty() || !accent.isEmpty()) {
            sb.append("Brand colours to echo (restrained, not a colour splash): ");
            if (!primary.isEmpty()) {
                sb.append(primary);
            }
            if (!accent.isEmpty()) {
                if (!primary.isEmpty()) {
                    sb.append(" and ");
                }
                sb.append(accent);
            }
            sb.append('\n');
        }
        sb.append('\n');
        if (!ask.isEmpty()) {
            sb.append("Merchant's description:\n").append(ask).append("\n\n");
        } else {
            sb.append("Invent a simple, memorable mark from the shop name.\n\n");
        }
        sb.append("""
                Design rules:
                - Square 1:1 composition, mark centred, generous padding
                - Clean, simple, memorable; must read at 32 pixels
                - Flat or lightly shaded vector look. Not a photograph, not 3D, not a mockup
                - No watermarks, no app UI, no business cards, no storefront photos
                - Transparent background if possible; otherwise one solid colour
                - Include the shop name as lettering only if the merchant asked for text
                - No extra slogans or taglines
                """);

        String out = sb.toString().strip();
        return out.length() <= MAX_PROMPT ? out : out.substring(0, MAX_PROMPT);
    }

    static boolean hasEnoughInput(String prompt, String shopName) {
        return !clean(prompt, 600).isEmpty() || !clean(shopName, 120).isEmpty();
    }

    private static String hexOrEmpty(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return HEX.matcher(trimmed).matches() ? trimmed.toUpperCase(Locale.ROOT) : "";
    }

    private static String clean(String value, int max) {
        if (value == null) {
            return "";
        }
        String stripped = CONTROL.matcher(value).replaceAll("").strip();
        if (stripped.isEmpty()) {
            return "";
        }
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }
}
