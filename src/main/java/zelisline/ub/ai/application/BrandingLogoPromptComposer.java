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
        OG,
        APP_ICON
    }

    private static final int MAX_PROMPT = 4_000;
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\n\t]]");
    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /**
     * Used when the merchant taps Generate without describing the mark.
     * Applied only to the canonical light logo; every other asset is stamped
     * from that file so the kit stays one mark.
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
        if (isDerived(resolved)) {
            if (!ask.isEmpty()) {
                sb.append("Original brief for this mark (do not invent a new one):\n")
                        .append(ask)
                        .append("\n\n");
            }
        } else if (!ask.isEmpty()) {
            sb.append("Merchant's description:\n").append(ask).append("\n\n");
        } else {
            sb.append(DEFAULT_CONCEPT.strip()).append("\n\n");
        }
        switch (resolved) {
            case LIGHT -> sb.append("""
                    Asset: LOGO for LIGHT chrome (canonical mark)
                    Isolated mark only. Darker ink and brand colour. Must sit on white UI, \
                    receipts, and emails with no extra plate. This file is the source: dark \
                    logo, favicon, home-screen icon, and share image will all be stamped \
                    from this exact shape, so keep the silhouette simple and readable in \
                    one colour.

                    """);
            case DARK -> sb.append("""
                    Asset: DARK VARIANT of the attached light logo
                    The attached image is the shop's light-chrome logo. Recolor it only. \
                    Keep every shape, letter, layout, spacing, and proportion identical. \
                    Do not redesign, simplify, add, or remove anything. \
                    Swap ink to white, cream, or a bright brand tint so it reads on navy, \
                    black, or deep brand panels. Transparent background. No extra plate. \
                    Never a light mark trapped inside a white square.

                    """);
            case FAVICON -> sb.append("""
                    Asset: FAVICON / TAB ICON from the attached logo
                    The attached image is the shop's light-chrome logo. Do not invent a \
                    new symbol. Extract the core glyph: drop any wordmark or tagline. \
                    Stamp it as a browser tab icon: fill a rounded square edge to edge \
                    with a solid brand-primary field. Centre the glyph in white or cream \
                    with a little padding. Must read at 16 pixels. Opaque. No photo, \
                    no letterboxing, no new iconography.

                    """);
            case APP_ICON -> sb.append("""
                    Asset: HOME SCREEN APP ICON from the attached logo
                    The attached image is the shop's existing light-chrome logo. Keep that \
                    exact glyph. Do not invent a new mark. Rebuild it as an iOS/Android \
                    home-screen icon: opaque rounded-square that fills the frame. \
                    Solid brand-primary field. Glyph centred with ~20 percent safe inset \
                    so a circular mask will not crop it. If the logo includes lettering, \
                    drop the wordmark and keep the symbol; if it is only lettering, keep \
                    the letterforms. High contrast. No fake phone, no browser chrome, \
                    no transparency.

                    """);
            case OG -> sb.append("""
                    Asset: SOCIAL SHARE IMAGE from the attached logo
                    The attached image is the shop's light-chrome logo. Use that exact \
                    mark. Do not draw a different one. Compose a square WhatsApp/Facebook \
                    link-preview card. Brand-primary field (not white, not a photo of a \
                    website). Place the mark large and centred in light ink so it reads \
                    on that saturated field. Shop name in one clean sans-serif line under \
                    the mark if it fits. Optional: a very faint enlarged echo of the same \
                    glyph in the background, under 8 percent opacity, never a second logo. \
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
            if (resolved == Theme.DARK) {
                sb.append("""
                    - The attached light logo is the source of truth. Recolor ink only.
                    """);
            }
        } else if (resolved == Theme.APP_ICON) {
            sb.append("""
                    Design rules:
                    - Output THIS asset only. Do not draw a comparison sheet. Do not label the image.
                    - Square 1:1, fills the frame, no empty letterbox bars
                    - Opaque PNG. No alpha. No white rectangle behind the mark.
                    - Flat vector look. Not a photograph, not 3D, not a mockup of a phone
                    - No watermarks, no UI chrome, no business cards
                    - The attached light logo is the source of truth. Stamp that glyph; do not invent.
                    """);
        } else {
            sb.append("""
                    Design rules:
                    - Output THIS asset only. Do not draw a comparison sheet. Do not label the image.
                    - Square 1:1, fills the frame, no empty letterbox bars
                    - Opaque PNG. No alpha. No white rectangle behind the mark.
                    - Flat vector look. Not a photograph, not 3D, not a mockup of a browser
                    - No watermarks, no UI chrome, no business cards
                    - Never put a white rectangle behind the mark
                    - The attached light logo is the source of truth. Stamp that glyph; do not invent.
                    """);
        }

        String out = sb.toString().strip();
        return out.length() <= MAX_PROMPT ? out : out.substring(0, MAX_PROMPT);
    }

    static boolean hasEnoughInput(String prompt, String shopName) {
        return true;
    }

    private static boolean isDerived(Theme theme) {
        return theme != Theme.LIGHT;
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
