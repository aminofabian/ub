package zelisline.ub.billing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.domain.PlatformSubscriptionPlan;
import zelisline.ub.platform.email.application.PlatformCampaignEmailRenderer;
import zelisline.ub.platform.email.application.PlatformEmailMarkdown;
import zelisline.ub.tenancy.domain.Business;

/**
 * Email copy for subscription grace expiry campaigns (SUBSCRIPTION_BILLING_SCOPE.md §7).
 */
@Component
@RequiredArgsConstructor
public class SubscriptionExpiryEmailRenderer {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
                    .withZone(ZoneId.of("Africa/Nairobi"));
    private static final String DEFAULT_RENEWAL_PATH = "/business/billing/renew";

    private final PlatformCampaignEmailRenderer cardRenderer;

    public RenderedEmail render(
            Business business,
            PlatformSubscriptionPlan plan,
            int stepDay,
            int graceDays,
            Instant graceStartedAt,
            Instant graceEndsAt,
            String renewalUrl,
            String shopOrigin,
            boolean autoBlockEnabled
    ) {
        String businessName = business.getName() != null ? business.getName().trim() : "Your shop";
        BigDecimal amount = plan != null ? plan.getMonthlyPriceKes() : BigDecimal.ZERO;
        String currency = business.getCurrency() != null ? business.getCurrency() : "KES";
        int daysAgo = Math.max(1, stepDay);
        int daysRemaining = Math.max(0, graceDays - stepDay);
        String amountLabel = currency + " " + amount.stripTrailingZeros().toPlainString();
        String shop = resolveShopOrigin(shopOrigin, renewalUrl);
        String link = renewalUrl != null && !renewalUrl.isBlank()
                ? renewalUrl.trim()
                : shop + DEFAULT_RENEWAL_PATH;

        if (!autoBlockEnabled) {
            return autoBlockOffReminder(businessName, amountLabel, link, shop);
        }
        if (stepDay == 2) {
            return detailed(businessName, plan, amountLabel, graceStartedAt, graceEndsAt, link, shop);
        }
        if (stepDay >= 15) {
            return finalNotice(businessName, amountLabel, link, shop);
        }
        return shortReminder(businessName, daysAgo, daysRemaining, amountLabel, link, shop);
    }

    /**
     * Auto-block is off: the shop keeps working after grace, so the copy must
     * not promise a suspension that will never happen.
     */
    private RenderedEmail autoBlockOffReminder(
            String businessName,
            String amountLabel,
            String link,
            String shopOrigin
    ) {
        String subject = "Renew your Kiosk subscription when you can";
        String text = """
                Hi,

                %s's subscription has expired, but automatic blocking is off, so your shop is still working.

                Amount due: %s
                Renew: %s

                Your inventory, sales history, and settings are safe.
                """.formatted(businessName, amountLabel, link);

        String bodyHtml = """
                <p style="font-family:%s;font-size:15px;line-height:1.65;color:%s;margin:0 0 12px;">
                  <strong>%s</strong>&rsquo;s subscription has expired, but automatic blocking is off,
                  so your shop is still working.
                </p>
                <p style="font-family:%s;font-size:14px;line-height:1.65;color:%s;margin:0 0 12px;">
                  Amount due: <strong>%s</strong>
                </p>
                <p style="font-family:%s;font-size:14px;line-height:1.65;color:%s;margin:0;">
                  Renew when you can. Your inventory, sales history, and settings are safe.
                </p>
                """.formatted(
                PlatformCampaignEmailRenderer.FONT_SANS, PlatformCampaignEmailRenderer.TEXT,
                PlatformEmailMarkdown.escape(businessName),
                PlatformCampaignEmailRenderer.FONT_SANS, PlatformCampaignEmailRenderer.TEXT, amountLabel,
                PlatformCampaignEmailRenderer.FONT_SANS, PlatformCampaignEmailRenderer.MUTED);

        String html = cardRenderer.renderHtml(
                subject,
                bodyHtml,
                "Renew subscription",
                link,
                shopOrigin,
                "Renew " + businessName + " when you can");
        return new RenderedEmail(subject, text, html);
    }

    private RenderedEmail detailed(
            String businessName,
            PlatformSubscriptionPlan plan,
            String amountLabel,
            Instant expiredAt,
            Instant graceEndsAt,
            String link,
            String shopOrigin
    ) {
        String tier = plan != null ? plan.getDisplayName() : "Current plan";
        String subject = "Your Kiosk subscription has expired — renew to keep your shop running";
        String expired = expiredAt != null ? DATE_FMT.format(expiredAt) : "recently";
        String graceEnd = graceEndsAt != null ? DATE_FMT.format(graceEndsAt) : "soon";
        String text = """
                Hi,

                Your %s subscription expired on %s.

                Plan: %s
                Amount due: %s
                Grace ends: %s — after that, POS, storefront, and staff access will be suspended. Your data is not deleted.

                Renew: %s
                """.formatted(businessName, expired, tier, amountLabel, graceEnd, link);

        String bodyHtml = """
                <p style="font-family:%s;font-size:15px;line-height:1.65;color:%s;margin:0 0 16px;">
                  Your <strong>%s</strong> subscription expired on <strong>%s</strong>.
                </p>
                <ul style="font-family:%s;font-size:14px;line-height:1.65;color:%s;margin:0 0 16px;padding-left:20px;">
                  <li>Plan: <strong>%s</strong></li>
                  <li>Amount due: <strong>%s</strong></li>
                  <li>Grace ends: <strong>%s</strong></li>
                </ul>
                <p style="font-family:%s;font-size:14px;line-height:1.65;color:%s;margin:0 0 8px;">
                  If you don&rsquo;t renew before grace ends, these stop working:
                </p>
                <ul style="font-family:%s;font-size:14px;line-height:1.65;color:%s;margin:0 0 16px;padding-left:20px;">
                  <li>Point of sale and cashier access</li>
                  <li>Online storefront orders</li>
                  <li>Staff logins</li>
                </ul>
                <p style="font-family:%s;font-size:14px;line-height:1.65;color:%s;margin:0;">
                  Your inventory, sales history, and settings are kept safe — nothing is deleted.
                </p>
                """.formatted(
                PlatformCampaignEmailRenderer.FONT_SANS, PlatformCampaignEmailRenderer.TEXT, businessName, expired,
                PlatformCampaignEmailRenderer.FONT_SANS, PlatformCampaignEmailRenderer.TEXT, tier, amountLabel, graceEnd,
                PlatformCampaignEmailRenderer.FONT_SANS, PlatformCampaignEmailRenderer.MUTED,
                PlatformCampaignEmailRenderer.FONT_SANS, PlatformCampaignEmailRenderer.MUTED,
                PlatformCampaignEmailRenderer.FONT_SANS, PlatformCampaignEmailRenderer.MUTED);

        String html = cardRenderer.renderHtml(
                subject,
                bodyHtml,
                "Renew subscription",
                link,
                shopOrigin,
                "Renew " + businessName + " before services are suspended");
        return new RenderedEmail(subject, text, html);
    }

    private RenderedEmail shortReminder(
            String businessName,
            int daysAgo,
            int daysRemaining,
            String amountLabel,
            String link,
            String shopOrigin
    ) {
        String subject = daysRemaining <= 2
                ? "Last days to renew your Kiosk subscription"
                : "Reminder: renew your Kiosk subscription";
        String text = """
                Your subscription expired %d day(s) ago. You have %d day(s) remaining before your services are suspended.

                Amount due: %s
                Renew: %s
                """.formatted(daysAgo, Math.max(1, daysRemaining), amountLabel, link);

        String bodyHtml = """
                <p style="font-family:%s;font-size:15px;line-height:1.65;color:%s;margin:0 0 12px;">
                  Your <strong>%s</strong> subscription expired <strong>%d</strong> day(s) ago.
                  You have <strong>%d</strong> day(s) remaining before services are suspended.
                </p>
                <p style="font-family:%s;font-size:15px;line-height:1.65;color:%s;margin:0;">
                  Amount due: <strong>%s</strong>
                </p>
                """.formatted(
                PlatformCampaignEmailRenderer.FONT_SANS, PlatformCampaignEmailRenderer.TEXT,
                PlatformEmailMarkdown.escape(businessName), daysAgo, Math.max(1, daysRemaining),
                PlatformCampaignEmailRenderer.FONT_SANS, PlatformCampaignEmailRenderer.TEXT, amountLabel);

        String html = cardRenderer.renderHtml(subject, bodyHtml, "Renew subscription", link, shopOrigin, subject);
        return new RenderedEmail(subject, text, html);
    }

    private RenderedEmail finalNotice(String businessName, String amountLabel, String link, String shopOrigin) {
        String subject = "Final notice: your Kiosk services will be suspended today";
        String text = """
                Final notice: Your Kiosk subscription has been expired for 15 days. Your services will be suspended today unless you renew.

                Amount due: %s
                Renew now: %s
                """.formatted(amountLabel, link);

        String bodyHtml = """
                <p style="font-family:%s;font-size:15px;line-height:1.65;color:%s;margin:0 0 12px;">
                  Final notice: <strong>%s</strong> has been in grace for 15 days.
                  Your Kiosk services will be <strong>suspended today</strong> unless you renew.
                </p>
                <p style="font-family:%s;font-size:15px;line-height:1.65;color:%s;margin:0;">
                  Amount due: <strong>%s</strong>
                </p>
                """.formatted(
                PlatformCampaignEmailRenderer.FONT_SANS, PlatformCampaignEmailRenderer.TEXT,
                PlatformEmailMarkdown.escape(businessName),
                PlatformCampaignEmailRenderer.FONT_SANS, PlatformCampaignEmailRenderer.TEXT, amountLabel);

        String html = cardRenderer.renderHtml(subject, bodyHtml, "Renew now", link, shopOrigin, subject);
        return new RenderedEmail(subject, text, html);
    }

    public String renderDayZeroSms(String businessName, int graceDays, String renewalUrl) {
        return "Your %s subscription has expired. You have %d days to renew before your Kiosk services are suspended. Renew here: %s"
                .formatted(businessName, graceDays, renewalUrl);
    }

    public String renderAutoBlockOffSms(String businessName, String renewalUrl) {
        return "Your %s subscription has expired. Automatic blocking is off, so your shop is still working. Renew when you can: %s"
                .formatted(businessName, renewalUrl);
    }

    public String renderDayFifteenSms(String renewalUrl) {
        return "Final notice: Your Kiosk subscription grace period ends today. Renew now to avoid suspension: "
                + renewalUrl;
    }

    private static String resolveShopOrigin(String shopOrigin, String renewalUrl) {
        if (shopOrigin != null && !shopOrigin.isBlank()) {
            String trimmed = shopOrigin.trim();
            return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        }
        if (renewalUrl != null && !renewalUrl.isBlank()) {
            try {
                java.net.URI uri = java.net.URI.create(renewalUrl.trim());
                if (uri.getScheme() != null && uri.getHost() != null) {
                    int port = uri.getPort();
                    return port > 0
                            ? uri.getScheme() + "://" + uri.getHost() + ":" + port
                            : uri.getScheme() + "://" + uri.getHost();
                }
            } catch (RuntimeException ignored) {
                // Fall through.
            }
        }
        return "https://kiosk.ke";
    }

    public record RenderedEmail(String subject, String text, String html) {
    }
}
