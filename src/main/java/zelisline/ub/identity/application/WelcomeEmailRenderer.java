package zelisline.ub.identity.application;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Component;

import zelisline.ub.support.api.dto.SupportWelcomeCardDto;

/**
 * Automatic welcome email sent immediately after self-service signup.
 * Wordmarked as Kiosk (platform), not the tenant shop brand.
 */
@Component
public class WelcomeEmailRenderer {

    static final String GREEN = "#28A745";
    static final String PAGE_BG = "#F4F5F4";
    static final String CARD_BG = "#FFFFFF";
    static final String BORDER = "#E8EAE8";
    static final String TEXT = "#14201A";
    static final String MUTED = "#5C6B63";
    static final String HAIRLINE = "#EEF0EE";
    static final String FONT_SANS =
            "'DM Sans', 'Segoe UI', Roboto, Helvetica, Arial, sans-serif";
    static final String FONT_SERIF =
            "'Cormorant Garamond', Georgia, 'Times New Roman', serif";

    static final String SUBJECT = "Welcome to Kiosk! \uD83C\uDF89";
    public static final String SUPPORT_PHONE = "0714 282 874";
    public static final String SUPPORT_EMAIL = "admin@kiosk.ke";

    private static final String[] HELP_ITEMS = {
            "\uD83D\uDECD\uFE0F Setting up your online store",
            "\uD83C\uDFA8 Custom themes & website designs",
            "\uD83C\uDF10 Custom domains",
            "\uD83D\uDCF1 M-Pesa integration",
            "\u2699\uFE0F Custom features and adjustments",
            "\uD83D\uDCE6 Product and inventory setup",
            "\uD83D\uDCA1 General guidance on using Kiosk",
    };

    /** Plain labels for the support-chat welcome card (no emoji noise in the bubble). */
    private static final String[] HELP_ITEMS_CHAT = {
            "Getting the online store live",
            "Themes and custom domains",
            "M-Pesa on the till",
            "Products and inventory",
            "Custom tweaks when something’s missing",
    };

    public String renderSubject() {
        return SUBJECT;
    }

    public String renderPlainText(String recipientName, String businessName) {
        String name = displayName(recipientName);
        String business = displayBusiness(businessName);
        StringBuilder help = new StringBuilder();
        for (String item : HELP_ITEMS) {
            help.append(item).append('\n');
        }
        return """
                Hi %s,

                Welcome to Kiosk — we’re excited to have %s on board!

                Your store is ready to grow with you, and our team is here to help you get the most out of it.

                You can reach out to us anytime for help with:

                %s
                All of these setup, customization, and support services are completely free of charge. We want to make sure your store works the way you need it to.

                If you have an idea, a question, or simply don't know where to start, just reach out to us. Our team is happy to help.

                Welcome aboard! \uD83D\uDE80

                Kiosk Team
                \uD83D\uDCDE %s
                \uD83D\uDCE7 %s
                """.formatted(name, business, help, SUPPORT_PHONE, SUPPORT_EMAIL).strip();
    }

    /** Structured welcome for the tenant↔platform support thread (chat card). */
    public SupportWelcomeCardDto toWelcomeCard(String recipientName, String businessName) {
        return new SupportWelcomeCardDto(
                displayName(recipientName),
                displayBusiness(businessName),
                SUPPORT_PHONE,
                SUPPORT_EMAIL,
                List.copyOf(Arrays.asList(HELP_ITEMS_CHAT)));
    }

    public String renderHtml(String recipientName, String businessName) {
        String name = escape(displayName(recipientName));
        String business = escape(displayBusiness(businessName));
        String helpRows = renderHelpRows();

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Welcome to Kiosk</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Cormorant+Garamond:wght@500;600&family=DM+Sans:wght@400;500;600;700&display=swap" rel="stylesheet">
                </head>
                <body style="margin:0;padding:0;background-color:%s;font-family:%s;-webkit-font-smoothing:antialiased;">
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:%s;padding:40px 16px 56px;">
                  <tr>
                    <td align="center">
                      <table role="presentation" width="560" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%%;background-color:%s;border:1px solid %s;border-radius:4px;overflow:hidden;">
                        <tr>
                          <td style="background-color:%s;padding:0;">
                            <div style="height:3px;background-color:%s;line-height:3px;font-size:0;">&nbsp;</div>
                            <div style="padding:28px 36px 24px;text-align:left;">
                              <div style="display:inline-block;width:40px;height:40px;border-radius:10px;background-color:%s;text-align:center;line-height:40px;margin-bottom:14px;">
                                <span style="font-family:%s;font-size:15px;font-weight:600;color:#FFFFFF;letter-spacing:0.04em;vertical-align:middle;">K</span>
                              </div>
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
                              Welcome
                            </div>
                            <div style="font-family:%s;font-size:34px;font-weight:500;color:%s;line-height:1.15;letter-spacing:-0.02em;margin-bottom:14px;">
                              You&rsquo;re in
                            </div>
                            <div style="font-family:%s;font-size:15px;font-weight:400;color:%s;line-height:1.55;margin-bottom:12px;">
                              Hi %s,
                            </div>
                            <div style="font-family:%s;font-size:15px;font-weight:400;color:%s;line-height:1.55;margin-bottom:12px;">
                              Welcome to Kiosk — we&rsquo;re excited to have <strong style="font-weight:600;color:%s;">%s</strong> on board!
                            </div>
                            <div style="font-family:%s;font-size:14px;font-weight:400;color:%s;line-height:1.55;">
                              Your store is ready to grow with you, and our team is here to help you get the most out of it.
                            </div>
                          </td>
                        </tr>
                        <tr>
                          <td style="background-color:%s;padding:24px 36px 8px;">
                            <div style="font-family:%s;font-size:14px;font-weight:500;color:%s;line-height:1.55;margin-bottom:14px;">
                              You can reach out to us anytime for help with:
                            </div>
                            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="border:1px solid %s;border-radius:4px;background-color:%s;">
                              %s
                            </table>
                          </td>
                        </tr>
                        <tr>
                          <td style="background-color:%s;padding:24px 36px 28px;">
                            <div style="font-family:%s;font-size:14px;font-weight:400;color:%s;line-height:1.55;margin-bottom:14px;">
                              All of these setup, customization, and support services are <strong style="font-weight:600;color:%s;">completely free of charge</strong>. We want to make sure your store works the way you need it to.
                            </div>
                            <div style="font-family:%s;font-size:14px;font-weight:400;color:%s;line-height:1.55;">
                              If you have an idea, a question, or simply don&rsquo;t know where to start, just reach out to us. Our team is happy to help.
                            </div>
                          </td>
                        </tr>
                        <tr>
                          <td style="background-color:%s;padding:28px 36px 32px;border-top:1px solid %s;text-align:left;">
                            <div style="font-family:%s;font-size:15px;font-weight:500;color:%s;line-height:1.55;margin-bottom:16px;">
                              Welcome aboard! \uD83D\uDE80
                            </div>
                            <div style="font-family:%s;font-size:14px;font-weight:600;color:%s;line-height:1.55;margin-bottom:6px;">
                              Kiosk Team
                            </div>
                            <div style="font-family:%s;font-size:13px;font-weight:400;color:%s;line-height:1.65;">
                              \uD83D\uDCDE <a href="tel:+254714282874" style="color:%s;text-decoration:none;">%s</a><br>
                              \uD83D\uDCE7 <a href="mailto:%s" style="color:%s;text-decoration:none;">%s</a>
                            </div>
                            <div style="font-family:%s;font-size:11px;font-weight:400;color:#9AA39D;margin-top:20px;">
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
                PAGE_BG, FONT_SANS,
                PAGE_BG,
                CARD_BG, BORDER,
                CARD_BG,
                GREEN,
                GREEN,
                FONT_SANS,
                FONT_SANS, TEXT,
                FONT_SANS, MUTED,
                CARD_BG, HAIRLINE,
                FONT_SANS, GREEN,
                FONT_SERIF, TEXT,
                FONT_SANS, MUTED, name,
                FONT_SANS, MUTED, TEXT, business,
                FONT_SANS, MUTED,
                CARD_BG,
                FONT_SANS, TEXT,
                BORDER, PAGE_BG,
                helpRows,
                CARD_BG,
                FONT_SANS, MUTED, TEXT,
                FONT_SANS, MUTED,
                CARD_BG, BORDER,
                FONT_SANS, TEXT,
                FONT_SANS, TEXT,
                FONT_SANS, MUTED, GREEN, SUPPORT_PHONE,
                SUPPORT_EMAIL, GREEN, SUPPORT_EMAIL,
                FONT_SANS,
                java.time.Year.now().getValue());
    }

    private static String renderHelpRows() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HELP_ITEMS.length; i++) {
            boolean last = i == HELP_ITEMS.length - 1;
            sb.append("""
                    <tr>
                      <td style="padding:12px 18px;%s;font-family:%s;font-size:14px;font-weight:400;color:%s;line-height:1.45;">
                        %s
                      </td>
                    </tr>
                    """.formatted(
                    last ? "" : "border-bottom:1px solid " + HAIRLINE,
                    FONT_SANS,
                    TEXT,
                    escape(HELP_ITEMS[i])));
        }
        return sb.toString();
    }

    static String displayName(String recipientName) {
        if (recipientName == null || recipientName.isBlank()) {
            return "there";
        }
        return recipientName.trim();
    }

    static String displayBusiness(String businessName) {
        if (businessName == null || businessName.isBlank()) {
            return "your business";
        }
        return businessName.trim();
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
