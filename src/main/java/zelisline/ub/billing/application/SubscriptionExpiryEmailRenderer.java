package zelisline.ub.billing.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
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

    private final PlatformCampaignEmailRenderer cardRenderer;

    @Value("${app.public.frontend-base-url:https://palmart.co.ke}")
    private String frontendBaseUrl;

    public RenderedEmail render(
            Business business,
            PlatformSubscriptionPlan plan,
            int stepDay,
            int graceDays,
            Instant graceStartedAt,
            Instant graceEndsAt,
            String renewalUrl
    ) {
        String businessName = business.getName() != null ? business.getName().trim() : "Your shop";
        BigDecimal amount = plan != null ? plan.getMonthlyPriceKes() : BigDecimal.ZERO;
        String currency = business.getCurrency() != null ? business.getCurrency() : "KES";
        int daysAgo = Math.max(1, stepDay);
        int daysRemaining = Math.max(0, graceDays - stepDay);
        String amountLabel = currency + " " + amount.stripTrailingZeros().toPlainString();
        String link = renewalUrl != null && !renewalUrl.isBlank() ? renewalUrl.trim() : renewUrl();

        if (stepDay == 2) {
            return detailed(businessName, plan, amountLabel, graceStartedAt, graceEndsAt, link);
        }
        if (stepDay >= 15) {
            return finalNotice(businessName, amountLabel, link);
        }
        return shortReminder(businessName, daysAgo, daysRemaining, amountLabel, link);
    }

    private RenderedEmail detailed(
            String businessName,
            PlatformSubscriptionPlan plan,
            String amountLabel,
            Instant expiredAt,
            Instant graceEndsAt,
            String link
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
                frontendBaseUrl,
                "Renew " + businessName + " before services are suspended");
        return new RenderedEmail(subject, text, html);
    }

    private RenderedEmail shortReminder(
            String businessName,
            int daysAgo,
            int daysRemaining,
            String amountLabel,
            String link
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

        String html = cardRenderer.renderHtml(subject, bodyHtml, "Renew subscription", link, frontendBaseUrl, subject);
        return new RenderedEmail(subject, text, html);
    }

    private RenderedEmail finalNotice(String businessName, String amountLabel, String link) {
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

        String html = cardRenderer.renderHtml(subject, bodyHtml, "Renew now", link, frontendBaseUrl, subject);
        return new RenderedEmail(subject, text, html);
    }

    public String renderDayZeroSms(String businessName, int graceDays, String renewalUrl) {
        return "Your %s subscription has expired. You have %d days to renew before your Kiosk services are suspended. Renew here: %s"
                .formatted(businessName, graceDays, renewalUrl);
    }

    public String renderDayFifteenSms(String renewalUrl) {
        return "Final notice: Your Kiosk subscription grace period ends today. Renew now to avoid suspension: "
                + renewalUrl;
    }

    private String renewUrl() {
        String base = frontendBaseUrl != null ? frontendBaseUrl.trim() : "";
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.isBlank() ? "https://palmart.co.ke/business/billing/renew" : base + "/business/billing/renew";
    }

    public record RenderedEmail(String subject, String text, String html) {
    }
}
