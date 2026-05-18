package zelisline.ub.identity.application;

import org.springframework.stereotype.Component;

/**
 * Branded HTML (and plain-text fallback) for tenant-scoped email verification.
 * Colors and copy adapt to the signup subdomain and stored tenant branding.
 */
@Component
public class EmailVerificationEmailRenderer {

    private static final String MUTED = "#6B7280";
    private static final String SURFACE = "#F8FAFC";
    private static final String CARD = "#FFFFFF";

    public String renderSubject(EmailVerificationBrandingContext branding) {
        return "Verify your " + branding.displayName() + " account";
    }

    public String renderPlainText(
            EmailVerificationBrandingContext branding,
            String recipientEmail,
            String verifyLink) {
        return """
                %s — verify your email

                %s

                We received a registration for %s.

                Activate your account:
                %s

                If you did not sign up, you can ignore this message.
                """.formatted(
                branding.displayName(),
                branding.tagline(),
                recipientEmail,
                verifyLink).strip();
    }

    public String renderHtml(
            EmailVerificationBrandingContext branding,
            String recipientEmail,
            String verifyLink) {
        String primary = branding.primaryColor();
        String accent = branding.accentColor();
        String display = escape(branding.displayName());
        String tagline = escape(branding.tagline());
        String email = escape(recipientEmail);
        String link = escape(verifyLink);
        String hostFooter = branding.host() != null
                ? "<br><span style=\"color:#94A3B8;\">Sent from " + escape(branding.host()) + "</span>"
                : "";

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Verify your email</title>
                </head>
                <body style="margin:0;padding:0;background-color:%s;font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:%s;padding:36px 16px 48px;">
                  <tr>
                    <td align="center">
                      <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%%;">
                        %s
                        <tr>
                          <td style="background-color:%s;padding:36px 32px 28px;border-radius:0 0 16px 16px;box-shadow:0 12px 40px rgba(15,23,42,0.08);">
                            <div style="font-size:13px;font-weight:600;color:%s;letter-spacing:0.6px;text-transform:uppercase;margin-bottom:10px;">
                              Welcome aboard
                            </div>
                            <div style="font-size:26px;font-weight:800;color:#0F172A;line-height:1.25;margin-bottom:12px;">
                              Verify your email
                            </div>
                            <div style="font-size:15px;color:%s;line-height:1.6;margin-bottom:22px;">
                              %s
                            </div>
                            <div style="font-size:14px;color:#334155;line-height:1.6;margin-bottom:26px;">
                              We received a registration for <strong style="color:#0F172A;">%s</strong>.
                              Tap the button below to activate your account.
                            </div>
                            <table role="presentation" cellpadding="0" cellspacing="0" style="margin:0 auto 24px;">
                              <tr>
                                <td align="center" style="border-radius:12px;background:linear-gradient(135deg,%s 0%%,%s 100%%);">
                                  <a href="%s" target="_blank" style="display:inline-block;padding:16px 36px;font-size:16px;font-weight:700;color:#FFFFFF;text-decoration:none;letter-spacing:0.3px;">
                                    Verify email address
                                  </a>
                                </td>
                              </tr>
                            </table>
                            <div style="font-size:12px;color:%s;line-height:1.6;text-align:center;margin-bottom:8px;">
                              Button not working? Copy and paste this link:
                            </div>
                            <div style="font-size:12px;color:%s;word-break:break-all;text-align:center;line-height:1.5;padding:0 8px;">
                              <a href="%s" style="color:%s;text-decoration:underline;">%s</a>
                            </div>
                            <div style="margin-top:28px;padding-top:20px;border-top:1px solid #E2E8F0;font-size:12px;color:%s;line-height:1.6;text-align:center;">
                              If you did not sign up, you can safely ignore this message.%s
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
                SURFACE,
                SURFACE,
                renderHeader(branding, display, primary, accent),
                CARD,
                accent,
                MUTED,
                tagline,
                email,
                primary,
                accent,
                link,
                MUTED,
                MUTED,
                link,
                primary,
                link,
                MUTED,
                hostFooter);
    }

    private String renderHeader(
            EmailVerificationBrandingContext branding,
            String display,
            String primary,
            String accent) {
        String logoBlock = branding.logoUrl() != null
                ? """
                <img src="%s" alt="%s" width="120" style="display:block;margin:0 auto 16px;max-width:120px;height:auto;border:0;">
                """.formatted(escape(branding.logoUrl()), display)
                : """
                <div style="font-size:42px;line-height:1;margin-bottom:8px;text-align:center;">&#128230;</div>
                """;

        String slugBadge = branding.slug() != null && !branding.slug().isBlank()
                ? """
                <div style="margin-top:12px;">
                  <span style="display:inline-block;background-color:rgba(255,255,255,0.2);border-radius:20px;padding:5px 14px;font-size:11px;font-weight:600;color:#FFFFFF;letter-spacing:0.5px;text-transform:uppercase;">
                    %s
                  </span>
                </div>
                """.formatted(escape(branding.slug()))
                : "";

        return """
                <tr>
                  <td style="background:linear-gradient(145deg,%s 0%%,%s 55%%,%s 100%%);padding:32px 28px 28px;border-radius:16px 16px 0 0;text-align:center;">
                    %s
                    <div style="font-size:24px;font-weight:800;color:#FFFFFF;letter-spacing:0.3px;line-height:1.2;">
                      %s
                    </div>
                    %s
                  </td>
                </tr>
                """.formatted(primary, accent, primary, logoBlock, display, slugBadge);
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
