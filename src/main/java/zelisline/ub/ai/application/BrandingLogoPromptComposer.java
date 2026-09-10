package zelisline.ub.ai.application;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the merchant's short description (or a default brand brief) into two
 * Images-API prompts: one mark for light chrome, one for dark chrome.
 */
final class BrandingLogoPromptComposer {

    enum Theme {
        LIGHT,
        DARK
    }

    private static final int MAX_PROMPT = 4_000;
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\n\t]]");
    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /**
     * Used when the merchant taps Generate without describing the mark.
     * Two separate image calls apply this brief (one per theme) so each
     * file is a usable logo, not a labeled comparison sheet.
     */
    static final String DEFAULT_CONCEPT = """
            Generate a professional, visually appealing logo that fits the overall brand identity \
            and existing application design. The mark should be consistent in concept and \
            recognizable as the same brand, but optimized for one interface theme (specified below).

            Use the application's existing theme colours as the foundation. The light and dark \
            versions should feel like part of the same visual system — not two completely different \
            designs. Maintain the same core iconography, typography, proportions, and brand identity, \
            while making appropriate colour adjustments for the theme.

            Keep the design clean, modern, simple, and memorable. Avoid overly complex details. \
            The logo should integrate naturally with the existing UI and work effectively across \
            different sizes and platforms, including web, mobile, favicons, social media, and printed \
            materials.

            The result should feel intentional, polished, and consistent with the overall application theme.
            """;

    private BrandingLogoPromptComposer() {}

    static String compose(
            String prompt,
            String shopName,
            String shopType,
            String primaryColor,
            String accentColor,
            Theme theme
    ) {
        Theme resolved = theme == null ? Theme.LIGHT : theme;
        String ask = clean(prompt, 600);
        String name = clean(shopName, 120);
        String type = clean(shopType, 80);
        String primary = hexOrEmpty(primaryColor);
        String accent = hexOrEmpty(accentColor);

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
            sb.append(DEFAULT_CONCEPT.strip()).append("\n\n");
        }
        if (resolved == Theme.LIGHT) {
            sb.append("""
                    Theme: LIGHT
                    Create a version specifically designed to work well on light backgrounds. \
                    Ensure sufficient contrast, clarity, and visual balance while using colours \
                    that complement the application's existing theme. Darker ink and brand colour \
                    on a transparent or pale field.

                    """);
        } else {
            sb.append("""
                    Theme: DARK
                    Create a corresponding version optimized for dark backgrounds. Adjust colours, \
                    contrast, and any necessary visual elements so the logo remains highly visible \
                    and visually appealing without losing its brand identity. Lighter ink and \
                    brighter brand colour on a transparent or deep field.

                    """);
        }
        sb.append("""
                Design rules:
                - Output THIS theme's mark only. Do not draw both versions. Do not label the image.
                - Square 1:1 composition, mark centred, generous padding
                - Clean, simple, memorable; must read at 32 pixels
                - Flat or lightly shaded vector look. Not a photograph, not 3D, not a mockup
                - No watermarks, no app UI, no business cards, no storefront photos
                - Transparent background if possible; otherwise one solid colour that matches the theme
                - Include the shop name as lettering only if the merchant asked for text
                - No extra slogans or taglines
                """);

        String out = sb.toString().strip();
        return out.length() <= MAX_PROMPT ? out : out.substring(0, MAX_PROMPT);
    }

    static boolean hasEnoughInput(String prompt, String shopName) {
        return true;
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
