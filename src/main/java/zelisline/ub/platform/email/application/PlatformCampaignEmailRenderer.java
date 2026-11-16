package zelisline.ub.platform.email.application;

import org.springframework.stereotype.Component;

/**
 * Card chrome for platform campaign mail — same tokens as verification mail,
 * always wordmarked Kiosk (operator From name).
 */
@Component
public class PlatformCampaignEmailRenderer {

    public static final String GREEN = "#28A745";
    public static final String PAGE_BG = "#F4F5F4";
    public static final String CARD_BG = "#FFFFFF";
    public static final String BORDER = "#E8EAE8";
    public static final String TEXT = "#14201A";
    public static final String MUTED = "#5C6B63";
    public static final String HAIRLINE = "#EEF0EE";
    public static final String FONT_SANS =
            "'DM Sans', 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
    public static final String FONT_SERIF =
            "'Cormorant Garamond', Georgia, 'Times New Roman', serif";
    public static final String FONT_MONO =
            "'SF Mono', Menlo, Consolas, 'Courier New', monospace";

    public String renderHtml(
            String subject,
            String bodyHtml,
            String ctaLabel,
            String continueUrl,
            String shopUrl
    ) {
        return renderHtml(subject, bodyHtml, ctaLabel, continueUrl, shopUrl, null);
    }

    public String renderHtml(
            String subject,
            String bodyHtml,
            String ctaLabel,
            String continueUrl,
            String shopUrl,
            String preheader
    ) {
        return renderHtml(new Content(
                subject, bodyHtml, ctaLabel, continueUrl, shopUrl, preheader, null, null, null));
    }

    /**
     * Chrome inputs for a campaign mail. {@code logoUrl} renders a real
     * {@code <img>} in the header (falls back to the "K" wordmark badge when
     * blank); {@code muteUrl} renders a quiet "Mute these tips" link in the
     * footer (nothing when blank) instead of a naked URL in the body;
     * {@code eyebrow} is the small uppercase label above the title (defaults to
     * "Account setup" when blank).
     */
    public record Content(
            String subject,
            String bodyHtml,
            String ctaLabel,
            String continueUrl,
            String shopUrl,
            String preheader,
            String logoUrl,
            String muteUrl,
            String eyebrow
    ) {}

    public String renderHtml(Content c) {
        String subject = c.subject();
        String bodyHtml = c.bodyHtml();
        String ctaLabel = c.ctaLabel();
        String continueUrl = c.continueUrl();
        String shopUrl = c.shopUrl();
        String preheader = c.preheader();
        String title = PlatformEmailMarkdown.escape(subject == null || subject.isBlank() ? "Kiosk" : subject);
        String cta = PlatformEmailMarkdown.escape(
                ctaLabel == null || ctaLabel.isBlank() ? "Continue setup" : ctaLabel);
        String link = PlatformEmailMarkdown.escape(continueUrl);
        String shop = PlatformEmailMarkdown.escape(shopUrl == null ? "" : shopUrl);
        String prehead = (preheader == null || preheader.isBlank())
                ? ""
                : "<div style=\"display:none;max-height:0;overflow:hidden;mso-hide:all;\">"
                        + PlatformEmailMarkdown.escape(preheader) + "&nbsp;&zwnj;</div>";
        String logoBlock = logoBlock(c.logoUrl());
        String muteBlock = muteBlock(c.muteUrl());
        String eyebrow = PlatformEmailMarkdown.escape(
                c.eyebrow() == null || c.eyebrow().isBlank() ? "Account setup" : c.eyebrow());

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Cormorant+Garamond:wght@500;600&family=DM+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
                </head>
                <body style="margin:0;padding:0;background-color:%s;font-family:%s;-webkit-font-smoothing:antialiased;">
                %s
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:%s;padding:40px 16px 56px;">
                  <tr>
                    <td align="center">
                      <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%%;background-color:%s;border:1px solid %s;border-radius:4px;overflow:hidden;">
                        <tr>
                          <td style="background-color:%s;padding:0;">
                            <div style="height:3px;background-color:%s;line-height:3px;font-size:0;">&nbsp;</div>
                            <div style="padding:28px 36px 24px;text-align:left;">
                              %s
                              <div style="font-family:%s;font-size:20px;font-weight:600;color:%s;letter-spacing:-0.02em;line-height:1.2;">
                                Kiosk
                              </div>
                              <div style="font-family:%s;font-size:12px;font-weight:400;color:%s;letter-spacing:0.02em;margin-top:6px;">
                                Point of sale, inventory, and online storefront.
                              </div>
                            </div>
                          </td>
                        </tr>
                        <tr>
                          <td style="background-color:%s;padding:8px 36px 8px;border-top:1px solid %s;">
                            <div style="font-family:%s;font-size:11px;font-weight:600;color:%s;letter-spacing:0.12em;text-transform:uppercase;margin-bottom:8px;">
                              %s
                            </div>
                            <div style="font-family:%s;font-size:28px;font-weight:500;color:%s;line-height:1.2;letter-spacing:-0.02em;margin-bottom:16px;">
                              %s
                            </div>
                            %s
                          </td>
                        </tr>
                        <tr>
                          <td style="background-color:%s;padding:8px 36px 28px;">
                            <table role="presentation" cellpadding="0" cellspacing="0" width="100%%" style="margin:8px 0 20px;">
                              <tr>
                                <td align="center" style="border-radius:4px;background-color:%s;">
                                  <a href="%s" target="_blank" style="display:inline-block;padding:15px 28px;font-family:%s;font-size:15px;font-weight:600;color:#FFFFFF;text-decoration:none;letter-spacing:0.01em;">
                                    %s
                                  </a>
                                </td>
                              </tr>
                            </table>
                            <div style="font-family:%s;font-size:12px;font-weight:400;color:%s;line-height:1.6;margin-bottom:10px;">
                              Button not working? Paste this link into your browser:
                            </div>
                            <div style="font-family:%s;font-size:11px;font-weight:400;color:%s;line-height:1.5;word-break:break-all;">
                              <a href="%s" style="color:%s;text-decoration:underline;">%s</a>
                            </div>
                          </td>
                        </tr>
                        <tr>
                          <td style="background-color:%s;padding:28px 36px 32px;border-top:1px solid %s;text-align:left;">
                            <div style="font-family:%s;font-size:12px;font-weight:400;color:%s;line-height:1.65;margin-bottom:16px;">
                              You&rsquo;re getting this because you signed up for Kiosk%s.
                            </div>
                            %s
                            <div style="font-family:%s;font-size:11px;font-weight:400;color:#9AA39D;">
                              Kiosk &nbsp;&middot;&nbsp; %s
                            </div>
                          </td>
                        </tr>
                      </table>
                    </td>
                  </tr>
                </table>
                </body>
                </html>
                """.formatted(
                title,
                PAGE_BG,
                FONT_SANS,
                prehead,
                PAGE_BG,
                CARD_BG,
                BORDER,
                CARD_BG,
                GREEN,
                logoBlock,
                FONT_SANS, TEXT,
                FONT_SANS, MUTED,
                CARD_BG, HAIRLINE,
                FONT_SANS, GREEN,
                eyebrow,
                FONT_SERIF, TEXT,
                title,
                bodyHtml == null ? "" : bodyHtml,
                CARD_BG,
                GREEN,
                link, FONT_SANS,
                cta,
                FONT_SANS, MUTED,
                FONT_MONO, MUTED, link, GREEN, link,
                CARD_BG, BORDER,
                FONT_SANS, MUTED,
                shop.isBlank() ? "" : " &middot; " + shop,
                muteBlock,
                FONT_SANS,
                java.time.Year.now().getValue());
    }

    /**
     * Header brand badge. A real {@code logoUrl} renders a 40px {@code <img>};
     * blank falls back to the green "K" wordmark badge so mail still looks
     * finished when no platform logo is configured.
     */
    private String logoBlock(String logoUrl) {
        if (logoUrl != null && !logoUrl.isBlank()) {
            String safe = PlatformEmailMarkdown.escape(logoUrl.trim());
            return """
                    <img src="%s" alt="Kiosk" width="40" height="40" style="display:block;width:40px;height:40px;object-fit:contain;border:0;border-radius:8px;margin-bottom:14px;">
                    """.formatted(safe);
        }
        return """
                <div style="display:inline-block;width:40px;height:40px;border-radius:10px;background-color:%s;text-align:center;line-height:40px;margin-bottom:14px;">
                  <span style="font-family:%s;font-size:15px;font-weight:600;color:#FFFFFF;letter-spacing:0.04em;vertical-align:middle;">K</span>
                </div>
                """.formatted(GREEN, FONT_SANS);
    }

    /** Quiet footer "Mute these tips" link; nothing when no mute URL is given. */
    private String muteBlock(String muteUrl) {
        if (muteUrl == null || muteUrl.isBlank()) {
            return "";
        }
        String safe = PlatformEmailMarkdown.escape(muteUrl.trim());
        return """
                <div style="font-family:%s;font-size:11px;font-weight:400;color:%s;line-height:1.6;margin-bottom:16px;">
                  <a href="%s" style="color:%s;text-decoration:underline;">Mute these tips</a>
                </div>
                """.formatted(FONT_SANS, MUTED, safe, MUTED);
    }
}
