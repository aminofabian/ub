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

@Component
@RequiredArgsConstructor
public class SubscriptionPreExpiryEmailRenderer {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
                    .withZone(ZoneId.of("Africa/Nairobi"));

    private final PlatformCampaignEmailRenderer cardRenderer;

    @Value("${app.public.frontend-base-url:https://palmart.co.ke}")
    private String frontendBaseUrl;

    public RenderedEmail render(
            Business business,
            PlatformSubscriptionPlan plan,
            Instant periodEndAt,
            int daysUntilExpiry,
            String renewalUrl
    ) {
        String businessName = business.getName() != null ? business.getName().trim() : "Your shop";
        String tier = plan != null ? plan.getDisplayName() : "Current plan";
        String currency = business.getCurrency() != null ? business.getCurrency() : "KES";
        BigDecimal monthly = plan != null ? plan.getMonthlyPriceKes() : BigDecimal.ZERO;
        String amountLabel = currency + " " + monthly.stripTrailingZeros().toPlainString();
        String renewsOn = periodEndAt != null ? DATE_FMT.format(periodEndAt) : "soon";
        String link = renewalUrl != null && !renewalUrl.isBlank() ? renewalUrl.trim() : renewUrl();

        String subject = "Your Kiosk subscription renews in " + daysUntilExpiry + " days";
        String text = """
                Hi,

                Your %s subscription (%s) renews on %s — %d day(s) from now.

                Amount due: %s per month
                Renew early: %s

                Renewing before expiry keeps your POS, storefront, and staff access uninterrupted.
                """.formatted(businessName, tier, renewsOn, daysUntilExpiry, amountLabel, link);

        String bodyHtml = """
                <p style="font-family:%s;font-size:15px;line-height:1.65;color:%s;margin:0 0 12px;">
                  Your <strong>%s</strong> subscription (<strong>%s</strong>) renews on
                  <strong>%s</strong> — <strong>%d</strong> day(s) from now.
                </p>
                <p style="font-family:%s;font-size:15px;line-height:1.65;color:%s;margin:0 0 12px;">
                  Amount due: <strong>%s</strong> per month
                </p>
                <p style="font-family:%s;font-size:14px;line-height:1.65;color:%s;margin:0;">
                  Renew early to avoid interruption to POS, storefront, and staff access.
                </p>
                """.formatted(
                PlatformCampaignEmailRenderer.FONT_SANS,
                PlatformCampaignEmailRenderer.TEXT,
                PlatformEmailMarkdown.escape(businessName),
                PlatformEmailMarkdown.escape(tier),
                renewsOn,
                daysUntilExpiry,
                PlatformCampaignEmailRenderer.FONT_SANS,
                PlatformCampaignEmailRenderer.TEXT,
                amountLabel,
                PlatformCampaignEmailRenderer.FONT_SANS,
                PlatformCampaignEmailRenderer.MUTED);

        String html = cardRenderer.renderHtml(
                subject,
                bodyHtml,
                "Renew subscription",
                link,
                frontendBaseUrl,
                "Renew " + businessName + " before " + renewsOn);
        return new RenderedEmail(subject, text, html);
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
