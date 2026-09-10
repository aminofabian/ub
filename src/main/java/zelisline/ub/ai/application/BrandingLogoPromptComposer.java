package zelisline.ub.ai.application;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns the merchant's short description (or a default brand brief) into
 * Images-API prompts for the shop mark and matching web assets.
 */
final class BrandingLogoPromptComposer {

    enum Theme {
        LIGHT,
        DARK,
        FAVICON,
        OG
    }

    private static final int MAX_PROMPT = 4_000;
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\n\t]]");
    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /**
     * Used when the merchant taps Generate without describing the mark.
     * Separate image calls apply this brief (one per asset) so each
     * file is usable on its own, not a labeled comparison sheet.
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
        sb.append("Professional shop identity for a Kenyan neighbourhood business.\n");
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
        switch (resolved) {
            case LIGHT -> sb.append("""
                    Asset: LOGO for LIGHT chrome
                    Isolated mark only. Darker ink and brand colour. Must sit on white UI, \
                    receipts, and emails with no extra plate.

                    """);
            case DARK -> sb.append("""
                    Asset: LOGO for DARK chrome
                    Isolated mark only. Lighter ink (white, cream, or a bright brand tint). \
                    Must sit on navy, black, or deep brand panels with no extra plate. \
                    Never a light mark trapped inside a white square.

                    """);
            case FAVICON -> sb.append("""
                    Asset: FAVICON / APP ICON
                    A simplified sibling of the same mark, built as a browser tab icon. \
                    Fill a rounded square edge to edge. Solid brand-primary tile. \
                    High-contrast glyph centred (white or cream). No wordmark, no tagline, \
                    no photo, no letterboxing. Must read at 16 pixels.

                    """);
            case OG -> sb.append("""
                    Asset: SOCIAL SHARE IMAGE
                    Square link-preview card for WhatsApp and Facebook. Brand-primary field \
                    (not white, not a photo of a website). The same mark large and centred \
                    in light ink so it reads on that dark or saturated field. \
                    Shop name in one clean sans-serif line under the mark if it fits. \
                    Generous padding. No browser chrome, no phone mockup, no white card \
                    behind the logo.

                    """);
        }
        if (resolved == Theme.LIGHT || resolved == Theme.DARK) {
            sb.append("""
                    Design rules:
                    - Output THIS asset only. Do not draw both versions. Do not label the image.
                    - Square 1:1 composition, mark centred, generous padding
                    - Clean, simple, memorable; must read at 32 pixels
                    - Flat or lightly shaded vector look. Not a photograph, not 3D, not a mockup
                    - No watermarks, no app UI, no business cards, no storefront photos
                    - Transparent background. PNG with alpha. No white square, card, sticker, \
                    badge backing, canvas, drop shadow box, or pale plate behind the mark.
                    - The file is the glyph itself, not a picture of a logo on a background
                    - Include the shop name as lettering only if the merchant asked for text
                    - No extra slogans or taglines
                    """);
        } else {
            sb.append("""
                    Design rules:
                    - Output THIS asset only. Do not draw a comparison sheet. Do not label the image.
                    - Square 1:1, fills the frame, no empty letterbox bars
                    - Flat vector look. Not a photograph, not 3D, not a mockup of a browser
                    - No watermarks, no UI chrome, no business cards
                    - Never put a white rectangle behind the mark
                    """);
        }

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
