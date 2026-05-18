package zelisline.ub.identity.application;

import org.springframework.stereotype.Component;

/**
 * Branded HTML verification email — clean card layout on mint green, hero
 * illustration, and primary CTA button. Matches the product verification mockup;
 * tenant logo and button colour come from {@link EmailVerificationBrandingContext}.
 */
@Component
public class EmailVerificationEmailRenderer {

    /** Mockup primary green */
    static final String GREEN = "#28A745";
    /** Darker green for logo tile and illustration accents */
    static final String GREEN_DARK = "#20863B";
    /** Page background (mint) */
    static final String BG_MINT = "#E9F0EA";
    /** Hero panel background */
    static final String HERO_BG = "#F0F2F1";
    static final String CARD = "#FFFFFF";
    static final String TEXT = "#111827";
    static final String MUTED = "#6C757D";

    /** Display type — matches frontend {@code --font-cormorant} / {@code .font-serif}. */
    static final String FONT_SERIF =
            "'Cormorant Garamond', Georgia, 'Times New Roman', serif";
    /** UI type — matches frontend {@code --font-dm-sans} / {@code font-sans}. */
    static final String FONT_SANS =
            "'DM Sans', 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";

    public String renderSubject(EmailVerificationBrandingContext branding) {
        return "Verify your " + branding.displayName() + " account";
    }

    public String renderPlainText(
            EmailVerificationBrandingContext branding,
            String recipientEmail,
            String verifyLink) {
        return """
                %s — verify your email

                Please click the link below to confirm your email.

                We received a registration for %s.

                Confirm your email:
                %s

                If you did not request this, no worries — simply ignore this message.
                """.formatted(
                branding.displayName(),
                recipientEmail,
                verifyLink).strip();
    }

    public String renderHtml(
            EmailVerificationBrandingContext branding,
            String recipientEmail,
            String verifyLink) {
        String green = branding.primaryColor() != null ? branding.primaryColor() : GREEN;
        String greenDark = branding.accentColor() != null ? branding.accentColor() : GREEN_DARK;
        String display = escape(branding.displayName());
        String email = escape(recipientEmail);
        String link = escape(verifyLink);
        String logoInitials = initials(display);
        String hostLine = branding.host() != null
                ? escape(branding.host())
                : display;

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Verify your email</title>
                %s
                </head>
                <body style="margin:0;padding:0;background-color:%s;font-family:%s;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:%s;">
                  <tr>
                    <td align="center" style="padding:40px 16px 48px;">
                      %s
                      <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;background-color:%s;border-radius:16px;box-shadow:0 8px 32px rgba(17,24,39,0.08);overflow:hidden;">
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
                BG_MINT,
                FONT_SANS,
                BG_MINT,
                renderBackgroundBlobs(greenDark),
                CARD,
                renderLogoRow(branding, display, logoInitials, green, greenDark),
                renderHero(green, greenDark),
                renderBody(email, link, green),
                renderFooter(display, hostLine));
    }

    /**
     * Google Fonts — same families as {@code frontend/app/layout.tsx}
     * (Cormorant Garamond + DM Sans). Many clients load {@code link}; others fall
     * back to Georgia / system sans from inline {@code font-family}.
     */
    private static String renderFontHead() {
        return """
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Cormorant+Garamond:ital,wght@0,400;0,500;0,600;0,700;1,400&family=DM+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
                """;
    }

    /** Decorative circles behind the card (mint field). */
    private static String renderBackgroundBlobs(String greenDark) {
        return """
                <table role="presentation" width="600" cellpadding="0" cellspacing="0" style="max-width:600px;width:100%%;margin-bottom:-12px;">
                  <tr>
                    <td width="50%%" align="left" style="padding:0 0 0 8px;">
                      <div style="width:120px;height:120px;border-radius:60px;background-color:%s;opacity:0.35;"></div>
                    </td>
                    <td width="50%%" align="right" style="padding:0 8px 0 0;">
                      <div style="width:80px;height:80px;border-radius:40px;background-color:%s;opacity:0.25;margin-left:auto;"></div>
                    </td>
                  </tr>
                </table>
                """.formatted(greenDark, greenDark);
    }

    private String renderLogoRow(
            EmailVerificationBrandingContext branding,
            String display,
            String initials,
            String green,
            String greenDark) {
        String logoInner = branding.logoUrl() != null
                ? """
                <img src="%s" alt="%s" width="56" height="56" style="display:block;width:56px;height:56px;object-fit:contain;border:0;">
                """.formatted(escape(branding.logoUrl()), display)
                : """
                <span style="font-family:%s;font-size:22px;font-weight:600;color:#FFFFFF;letter-spacing:0.04em;">%s</span>
                """.formatted(FONT_SERIF, escape(initials));

        return """
                <tr>
                  <td align="center" style="padding:32px 32px 20px;background-color:%s;">
                    <table role="presentation" cellpadding="0" cellspacing="0">
                      <tr>
                        <td align="center" style="width:56px;height:56px;border-radius:12px;background-color:%s;text-align:center;vertical-align:middle;">
                          %s
                        </td>
                      </tr>
                    </table>
                  </td>
                </tr>
                """.formatted(CARD, branding.logoUrl() != null ? CARD : green, logoInner);
    }

    private String renderHero(String green, String greenDark) {
        return """
                <tr>
                  <td align="center" style="background-color:%s;padding:28px 24px 32px;">
                    %s
                  </td>
                </tr>
                """.formatted(HERO_BG, heroIllustrationSvg(green, greenDark));
    }

    private String renderBody(String email, String link, String green) {
        return """
                <tr>
                  <td style="background-color:%s;padding:8px 40px 36px;text-align:center;">
                    <h1 style="margin:0 0 12px;font-family:%s;font-size:32px;font-weight:600;color:%s;line-height:1.15;letter-spacing:-0.02em;">
                      Verify Your Email
                    </h1>
                    <p style="margin:0 0 8px;font-family:%s;font-size:16px;font-weight:400;color:%s;line-height:1.55;">
                      Please click the button below to confirm your email.
                    </p>
                    <p style="margin:0 0 28px;font-family:%s;font-size:14px;font-weight:400;color:%s;line-height:1.5;">
                      Account: <strong style="font-weight:600;color:%s;">%s</strong>
                    </p>
                    <table role="presentation" cellpadding="0" cellspacing="0" align="center" style="margin:0 auto 24px;">
                      <tr>
                        <td align="center" style="border-radius:8px;background-color:%s;">
                          <a href="%s" target="_blank" style="display:inline-block;padding:14px 32px;font-family:%s;font-size:16px;font-weight:600;color:#FFFFFF;text-decoration:none;">
                            Confirm your email
                          </a>
                        </td>
                      </tr>
                    </table>
                    <p style="margin:0 0 16px;font-family:%s;font-size:13px;font-weight:400;color:%s;line-height:1.55;">
                      If you did not request this, no worries — simply ignore this message.
                    </p>
                    <p style="margin:0;font-family:%s;font-size:12px;font-weight:400;color:%s;line-height:1.5;word-break:break-all;">
                      <a href="%s" style="color:%s;text-decoration:underline;">%s</a>
                    </p>
                  </td>
                </tr>
                """.formatted(
                CARD,
                FONT_SERIF, TEXT,
                FONT_SANS, TEXT,
                FONT_SANS, MUTED, TEXT, email,
                green, link, FONT_SANS,
                FONT_SANS, MUTED,
                FONT_SANS, MUTED, link, green, link);
    }

    private String renderFooter(String display, String hostLine) {
        return """
                <tr>
                  <td style="background-color:%s;padding:24px 32px 32px;border-top:1px solid #E5E7EB;text-align:center;">
                    <p style="margin:0 0 8px;font-family:%s;font-size:12px;font-weight:500;color:%s;line-height:1.6;">
                      %s
                    </p>
                    <p style="margin:0;font-family:%s;font-size:11px;font-weight:400;color:#9CA3AF;line-height:1.5;">
                      &copy; %s &nbsp;&middot;&nbsp; %s
                    </p>
                  </td>
                </tr>
                """.formatted(
                CARD,
                FONT_SANS,
                MUTED,
                escape(display),
                FONT_SANS,
                java.time.Year.now().getValue(),
                escape(hostLine));
    }

    /**
     * Inline SVG: shield (left) + open envelope (centre) — email-safe in Apple Mail,
     * Gmail app, and most modern clients; degrades to empty hero panel elsewhere.
     */
    private static String heroIllustrationSvg(String green, String greenDark) {
        return """
                <svg xmlns="http://www.w3.org/2000/svg" width="280" height="100" viewBox="0 0 280 100" role="img" aria-label="Secure email verification" style="display:block;margin:0 auto;max-width:280px;height:auto;">
                  <g transform="translate(24,18)">
                    <path fill="%s" d="M28 4L8 12v14c0 11 8 21 20 24 12-3 20-13 20-24V12L28 4z"/>
                    <path fill="#FFFFFF" d="M22 26l-6-6 2.8-2.8L22 20.4l9.2-9.2L34 14l-12 12z"/>
                  </g>
                  <g transform="translate(108,8)">
                    <rect x="0" y="20" width="64" height="44" rx="6" fill="%s"/>
                    <path fill="%s" d="M0 20 L32 42 L64 20 Z"/>
                    <rect x="14" y="36" width="36" height="28" rx="3" fill="#FFFFFF"/>
                    <line x1="20" y1="48" x2="44" y2="48" stroke="%s" stroke-width="3" stroke-linecap="round"/>
                    <line x1="20" y1="56" x2="38" y2="56" stroke="#D1D5DB" stroke-width="3" stroke-linecap="round"/>
                  </g>
                </svg>
                """.formatted(greenDark, green, greenDark, green);
    }

    private static String initials(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "UB";
        }
        String[] parts = displayName.trim().split("\\s+");
        if (parts.length >= 2) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
        }
        String one = parts[0];
        return one.length() >= 2
                ? one.substring(0, 2).toUpperCase()
                : one.toUpperCase();
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
