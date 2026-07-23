package zelisline.ub.identity.application;

import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * Branded HTML verification email — same visual system as storefront order
 * confirmations: cool neutral page wash, white card, primary accent bar,
 * Cormorant + DM Sans, and tenant colours / wordmark.
 *
 * <p>Platform default wordmark is {@code UB}; tenant shops use their display name.
 */
@Component
public class EmailVerificationEmailRenderer {

    /** Default primary — matches platform {@code BRAND_PRIMARY}. */
    static final String GREEN = "#28A745";
    /** Default accent — matches platform {@code BRAND_ACCENT}. */
    static final String GREEN_DARK = "#20863B";

    /** Page wash — cool neutral (same as order confirmation). */
    static final String PAGE_BG = "#F4F5F4";
    static final String CARD_BG = "#FFFFFF";
    static final String BORDER = "#E8EAE8";
    static final String TEXT = "#14201A";
    static final String MUTED = "#5C6B63";
    static final String HAIRLINE = "#EEF0EE";

    /** Matches frontend {@code --font-dm-sans}. */
    static final String FONT_SANS =
            "'DM Sans', 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
    /** Matches frontend {@code --font-cormorant}. */
    static final String FONT_SERIF =
            "'Cormorant Garamond', Georgia, 'Times New Roman', serif";
    static final String FONT_MONO =
            "'SF Mono', Menlo, Consolas, 'Courier New', monospace";

    public String renderSubject(EmailVerificationBrandingContext branding) {
        return "Verify your " + brandWordmark(branding) + " account";
    }

    public String renderPlainText(
            EmailVerificationBrandingContext branding,
            String recipientEmail,
            String verifyLink) {
        return renderPlainText(branding, null, recipientEmail, verifyLink);
    }

    public String renderPlainText(
            EmailVerificationBrandingContext branding,
            String recipientName,
            String recipientEmail,
            String verifyLink) {
        String brand = brandWordmark(branding);
        String greeting = greetingLine(recipientName);
        return """
                %s — verify your email

                %s

                We received a registration for %s on %s.

                Confirm your email:
                %s

                If you did not request this, no worries — simply ignore this message.

                — %s
                """.formatted(
                brand,
                greeting,
                recipientEmail,
                brand,
                verifyLink,
                brand).strip();
    }

    public String renderHtml(
            EmailVerificationBrandingContext branding,
            String recipientEmail,
            String verifyLink) {
        return renderHtml(branding, null, recipientEmail, verifyLink);
    }

    public String renderHtml(
            EmailVerificationBrandingContext branding,
            String recipientName,
            String recipientEmail,
            String verifyLink) {
        Palette palette = Palette.from(branding);
        String brand = brandWordmark(branding);
        String tagline = brandTagline(branding);
        String email = escape(recipientEmail);
        String link = escape(verifyLink);
        String hostLine = branding.host() != null && !branding.host().isBlank()
                ? branding.host().trim()
                : brand;

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Verify your email</title>
                %s
                </head>
                <body style="margin:0;padding:0;background-color:%s;font-family:%s;-webkit-font-smoothing:antialiased;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:%s;padding:40px 16px 56px;">
                  <tr>
                    <td align="center">
                      <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%%;background-color:%s;border:1px solid %s;border-radius:4px;overflow:hidden;">
                        %s
                        %s
                        %s
                        %s
                      </table>
                    </td>
                  </tr>
                </table>
                </body>
                </html>
                """.formatted(
                renderFontHead(),
                PAGE_BG,
                FONT_SANS,
                PAGE_BG,
                CARD_BG,
                BORDER,
                renderHeader(brand, tagline, branding, palette),
                renderHero(recipientName, brand, palette),
                renderBody(email, link, brand, palette),
                renderFooter(brand, hostLine));
    }

    private static String renderFontHead() {
        return """
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Cormorant+Garamond:wght@500;600&family=DM+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
                """;
    }

    private String renderHeader(
            String brand,
            String tagline,
            EmailVerificationBrandingContext branding,
            Palette palette) {
        String logoBlock = branding.logoUrl() != null
                ? """
                    <img src="%s" alt="%s" width="40" height="40" style="display:block;width:40px;height:40px;object-fit:contain;border:0;border-radius:8px;margin-bottom:14px;">
                    """.formatted(escape(branding.logoUrl()), escape(brand))
                : """
                    <div style="display:inline-block;width:40px;height:40px;border-radius:10px;background-color:%s;text-align:center;line-height:40px;margin-bottom:14px;">
                      <span style="font-family:%s;font-size:15px;font-weight:600;color:#FFFFFF;letter-spacing:0.04em;vertical-align:middle;">%s</span>
                    </div>
                    """.formatted(palette.primary, FONT_SANS, escape(initials(brand)));

        String taglineBlock = tagline.isBlank()
                ? ""
                : """
                    <div style="font-family:%s;font-size:12px;font-weight:400;color:%s;letter-spacing:0.02em;margin-top:6px;">
                      %s
                    </div>
                    """.formatted(FONT_SANS, MUTED, escape(tagline));

        return """
                <tr>
                  <td style="background-color:%s;padding:0;">
                    <div style="height:3px;background-color:%s;line-height:3px;font-size:0;">&nbsp;</div>
                    <div style="padding:28px 36px 24px;text-align:left;">
                      %s
                      <div style="font-family:%s;font-size:20px;font-weight:600;color:%s;letter-spacing:-0.02em;line-height:1.2;">
                        %s
                      </div>
                      %s
                    </div>
                  </td>
                </tr>
                """.formatted(
                CARD_BG,
                palette.primary,
                logoBlock,
                FONT_SANS, TEXT, escape(brand),
                taglineBlock);
    }

    private String renderHero(String recipientName, String brand, Palette palette) {
        String greeting = escape(greetingLine(recipientName));

        return """
                <tr>
                  <td style="background-color:%s;padding:8px 36px 8px;border-top:1px solid %s;">
                    <div style="font-family:%s;font-size:11px;font-weight:600;color:%s;letter-spacing:0.12em;text-transform:uppercase;margin-bottom:8px;">
                      Email verification
                    </div>
                    <div style="font-family:%s;font-size:34px;font-weight:500;color:%s;line-height:1.15;letter-spacing:-0.02em;margin-bottom:14px;">
                      Confirm it&rsquo;s you
                    </div>
                    <div style="font-family:%s;font-size:15px;font-weight:400;color:%s;line-height:1.55;margin-bottom:4px;">
                      %s
                    </div>
                    <div style="font-family:%s;font-size:14px;font-weight:400;color:%s;line-height:1.55;">
                      One tap activates your <strong style="font-weight:600;color:%s;">%s</strong> account — then you can stock shelves and start selling.
                    </div>
                  </td>
                </tr>
                """.formatted(
                CARD_BG, HAIRLINE,
                FONT_SANS, palette.primary,
                FONT_SERIF, TEXT,
                FONT_SANS, MUTED, greeting,
                FONT_SANS, MUTED, TEXT, escape(brand));
    }

    private String renderBody(String email, String link, String brand, Palette palette) {
        return """
                <tr>
                  <td style="background-color:%s;padding:24px 36px 28px;">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border:1px solid %s;border-radius:4px;background-color:%s;">
                      <tr>
                        <td style="padding:18px 20px;">
                          <div style="font-family:%s;font-size:10px;font-weight:600;color:%s;text-transform:uppercase;letter-spacing:0.1em;margin-bottom:6px;">
                            Sent to
                          </div>
                          <div style="font-family:%s;font-size:14px;font-weight:500;color:%s;word-break:break-all;">
                            %s
                          </div>
                        </td>
                      </tr>
                    </table>

                    <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="margin:24px 0 20px;">
                      <tr>
                        <td align="center" style="border-radius:4px;background-color:%s;">
                          <a href="%s" target="_blank" style="display:inline-block;padding:15px 28px;font-family:%s;font-size:15px;font-weight:600;color:#FFFFFF;text-decoration:none;letter-spacing:0.01em;">
                            Confirm your email
                          </a>
                        </td>
                      </tr>
                    </table>

                    <div style="font-family:%s;font-size:12px;font-weight:400;color:%s;line-height:1.6;margin-bottom:14px;">
                      Button not working? Paste this link into your browser:
                    </div>
                    <div style="font-family:%s;font-size:11px;font-weight:400;color:%s;line-height:1.5;word-break:break-all;">
                      <a href="%s" style="color:%s;text-decoration:underline;">%s</a>
                    </div>
                  </td>
                </tr>
                """.formatted(
                CARD_BG,
                BORDER, PAGE_BG,
                FONT_SANS, MUTED,
                FONT_MONO, TEXT, email,
                palette.primary, link, FONT_SANS,
                FONT_SANS, MUTED,
                FONT_MONO, MUTED, link, palette.primary, link);
    }

    private String renderFooter(String brand, String hostLine) {
        return """
                <tr>
                  <td style="background-color:%s;padding:28px 36px 32px;border-top:1px solid %s;text-align:left;">
                    <div style="font-family:%s;font-size:13px;font-weight:400;color:%s;line-height:1.65;margin-bottom:6px;">
                      If you did not create a %s account, you can ignore this message.
                    </div>
                    <div style="font-family:%s;font-size:12px;font-weight:400;color:%s;line-height:1.65;margin-bottom:20px;">
                      Questions? Reply to this email — we&rsquo;re happy to help.
                    </div>
                    <div style="font-family:%s;font-size:11px;font-weight:400;color:#9AA39D;">
                      %s &nbsp;&middot;&nbsp; %s &nbsp;&middot;&nbsp; %s
                    </div>
                  </td>
                </tr>
                """.formatted(
                CARD_BG, BORDER,
                FONT_SANS, TEXT, escape(brand),
                FONT_SANS, MUTED,
                FONT_SANS,
                escape(brand),
                escape(hostLine),
                java.time.Year.now().getValue());
    }

    static String brandWordmark(EmailVerificationBrandingContext branding) {
        if (branding == null) {
            return "UB";
        }
        String raw = branding.displayName();
        if (raw == null || raw.isBlank() || isPlatformPlaceholderName(raw)) {
            if (branding.slug() != null && !branding.slug().isBlank()
                    && !"ub".equalsIgnoreCase(branding.slug())) {
                return titleCaseSlug(branding.slug());
            }
            return "UB";
        }
        int pipe = raw.indexOf('|');
        if (pipe > 0) {
            String left = raw.substring(0, pipe).trim();
            if (!left.isBlank()) {
                return left;
            }
        }
        return raw.trim();
    }

    static String brandTagline(EmailVerificationBrandingContext branding) {
        if (branding == null) {
            return "Confirm your email to unlock your account.";
        }
        if (branding.tagline() != null && !branding.tagline().isBlank()) {
            return branding.tagline().trim();
        }
        String brand = brandWordmark(branding);
        if ("UB".equalsIgnoreCase(brand)) {
            return "Point of sale, inventory, and online storefront.";
        }
        return "You're one click away from " + brand + ".";
    }

    static String greetingLine(String recipientName) {
        String first = firstName(recipientName);
        if (first == null) {
            return "Hi there —";
        }
        return "Hi " + first + " —";
    }

    static String firstName(String recipientName) {
        if (recipientName == null || recipientName.isBlank()) {
            return null;
        }
        String first = recipientName.trim().split("\\s+")[0];
        if (first.isBlank()) {
            return null;
        }
        return Character.toUpperCase(first.charAt(0))
                + (first.length() > 1 ? first.substring(1) : "");
    }

    static boolean isPlatformPlaceholderName(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String n = name.strip();
        return n.equalsIgnoreCase("Kiosk")
                || n.equalsIgnoreCase("UB")
                || n.equals("🥬 Palmart");
    }

    static String titleCaseSlug(String slug) {
        String normalized = slug.replace('-', ' ').trim();
        if (normalized.isEmpty()) {
            return "UB";
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
        return sb.isEmpty() ? "UB" : sb.toString();
    }

    private static String initials(String displayName) {
        if (displayName == null || displayName.isBlank() || "UB".equalsIgnoreCase(displayName)) {
            return "UB";
        }
        String[] parts = displayName.trim().split("\\s+");
        if (parts.length >= 2) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase(Locale.ROOT);
        }
        String one = parts[0];
        return one.length() >= 2
                ? one.substring(0, 2).toUpperCase(Locale.ROOT)
                : one.toUpperCase(Locale.ROOT);
    }

    record Palette(String primary, String accent) {
        static Palette from(EmailVerificationBrandingContext branding) {
            String primary = sanitizeHex(
                    branding != null ? branding.primaryColor() : null,
                    GREEN);
            String accent = sanitizeHex(
                    branding != null ? branding.accentColor() : null,
                    GREEN_DARK);
            return new Palette(primary, accent);
        }
    }

    static String sanitizeHex(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String s = raw.strip();
        if (!s.startsWith("#")) {
            s = "#" + s;
        }
        if (s.length() == 4) {
            char r = s.charAt(1);
            char g = s.charAt(2);
            char b = s.charAt(3);
            s = "#" + r + r + g + g + b + b;
        }
        if (s.length() != 7) {
            return fallback;
        }
        for (int i = 1; i < 7; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return fallback;
            }
        }
        return s.toUpperCase(Locale.ROOT);
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
