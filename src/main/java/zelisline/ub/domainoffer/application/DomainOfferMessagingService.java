package zelisline.ub.domainoffer.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.domainoffer.domain.DomainOfferOrder;
import zelisline.ub.domainoffer.domain.DomainOfferThemeDirection;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.platform.email.application.PlatformCampaignEmailRenderer;
import zelisline.ub.platform.email.application.PlatformEmailMarkdown;
import zelisline.ub.tenancy.domain.Business;

/**
 * Renders and delivers the offer's email + in-app messages. Email uses the
 * platform campaign card (Kiosk wordmark); in-app uses the staff bell payload
 * contract ({@code title}, {@code body}, {@code actionUrl}).
 */
@Service
@RequiredArgsConstructor
public class DomainOfferMessagingService {

    private static final Logger log = LoggerFactory.getLogger(DomainOfferMessagingService.class);
    private static final Pattern TAG = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");
    private static final DateTimeFormatter SLA_FORMAT =
            DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.UK).withZone(ZoneId.of("Africa/Nairobi"));
    private static final String FROM_DISPLAY_NAME = "Kiosk";
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final NotificationService emailService;
    private final zelisline.ub.notifications.application.NotificationService inAppService;
    private final PlatformCampaignEmailRenderer emailRenderer;
    private final DomainOfferAudienceService audienceService;
    private final ObjectMapper objectMapper;

    public record DeliveryResult(boolean emailSent, boolean inAppSent, String error) {
    }

    public DeliveryResult sendTouch(DomainOfferOrder order, Business business, DomainOfferCopy.Touch touch) {
        Map<String, String> tags = tags(order, business);
        String payUrl = tags.get("payUrl");
        String subject = render(touch.subject(), tags);
        String body = render(touch.bodyMarkdown(), tags);
        boolean emailSent = sendEmail(
                order.getContactEmail(),
                subject,
                body,
                touch.cta(),
                payUrl,
                tags.get("shopUrl"),
                render(touch.preview(), tags),
                order,
                "touch:" + touch.index());
        boolean inAppSent = sendInApp(
                order,
                render(touch.inAppTitle(), tags),
                render(touch.inAppBody(), tags),
                payUrl,
                "touch:" + touch.index());
        return new DeliveryResult(emailSent, inAppSent, order.getLastEmailError());
    }

    public void sendPaymentConfirmation(DomainOfferOrder order, Business business) {
        Map<String, String> tags = tags(order, business);
        String payUrl = tags.get("payUrl");
        String subject = render(DomainOfferCopy.paymentSubject(), tags);
        String body = render(DomainOfferCopy.paymentBody(), tags);
        sendEmail(
                order.getContactEmail(),
                subject,
                body,
                "Track my setup",
                payUrl,
                tags.get("shopUrl"),
                "The 24-hour clock has started.",
                order,
                "paid");
        sendInApp(
                order,
                render(DomainOfferCopy.inAppPaymentTitle(), tags),
                render(DomainOfferCopy.inAppPaymentBody(), tags),
                payUrl,
                "paid");
    }

    public void sendDelivered(DomainOfferOrder order, Business business) {
        Map<String, String> tags = tags(order, business);
        String liveUrl = order.getDeliveredDomain() != null && !order.getDeliveredDomain().isBlank()
                ? "https://" + order.getDeliveredDomain().trim().toLowerCase(Locale.ROOT)
                : tags.get("shopUrl");
        String subject = render(DomainOfferCopy.deliveredSubject(), tags);
        String body = render(DomainOfferCopy.deliveredBody(), tags);
        sendEmail(
                order.getContactEmail(),
                subject,
                body,
                "Visit my shop",
                liveUrl,
                liveUrl,
                "Your shop is live on its own domain.",
                order,
                "delivered");
        sendInApp(
                order,
                render(DomainOfferCopy.inAppDeliveredTitle(), tags),
                render(DomainOfferCopy.inAppDeliveredBody(), tags),
                liveUrl,
                "delivered");
    }

    public void sendRegistrationPrompt(DomainOfferOrder order, Business business) {
        Map<String, String> tags = tags(order, business);
        String trackUrl = tags.get("payUrl");
        sendEmail(
                order.getContactEmail(),
                render(DomainOfferCopy.registrationSubject(), tags),
                render(DomainOfferCopy.registrationBody(), tags),
                "Track my setup",
                trackUrl,
                tags.get("shopUrl"),
                "Your domain registration is included in the KES 1,000.",
                order,
                "registration");
        sendInApp(
                order,
                render(DomainOfferCopy.inAppRegistrationTitle(), tags),
                render(DomainOfferCopy.inAppRegistrationBody(), tags),
                trackUrl,
                "registration");
    }

    public DeliveryResult sendResend(DomainOfferOrder order, Business business) {
        Map<String, String> tags = tags(order, business);
        String payUrl = tags.get("payUrl");
        boolean emailSent = sendEmail(
                order.getContactEmail(),
                render(DomainOfferCopy.resendSubject(), tags),
                render(DomainOfferCopy.resendBody(), tags),
                DomainOfferCopy.CTA,
                payUrl,
                tags.get("shopUrl"),
                "Your domain and theme link.",
                order,
                "resend:" + (Instant.now().getEpochSecond() / 60));
        boolean inAppSent = sendInApp(
                order,
                render(DomainOfferCopy.inAppResendTitle(), tags),
                render(DomainOfferCopy.inAppResendBody(), tags),
                payUrl,
                "resend:" + (Instant.now().getEpochSecond() / 60));
        return new DeliveryResult(emailSent, inAppSent, order.getLastEmailError());
    }

    /** Super Admin preview/test send. Uses a synthetic sample shop. */
    public DeliveryResult sendTestEmail(String toEmail, String superAdminName) {
        if (toEmail == null || toEmail.isBlank()) {
            return new DeliveryResult(false, false, "missing_email");
        }
        DomainOfferCopy.Touch touch = DomainOfferCopy.touches().getFirst();
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("name", firstName(superAdminName));
        tags.put("businessName", "Powermart");
        tags.put("currentHost", "powermart.kiosk.ke");
        tags.put("suggestedDomain", "powermart.co.ke");
        tags.put("price", money(DomainOfferOrder.DEFAULT_PRICE_CENTS));
        tags.put("payUrl", "https://powermart.kiosk.ke/custom-domain?t=preview-token");
        tags.put("shopUrl", "https://powermart.kiosk.ke");
        tags.put("reference", "DTO-PREVIEW");
        tags.put("slaDue", "tomorrow, 14:00");
        tags.put("themeDirection", "Warm market");
        try {
            String html = emailRenderer.renderHtml(
                    render(touch.subject(), tags),
                    PlatformEmailMarkdown.toHtml(render(touch.bodyMarkdown(), tags)),
                    touch.cta(),
                    tags.get("payUrl"),
                    tags.get("shopUrl"),
                    render(touch.preview(), tags));
            String text = PlatformEmailMarkdown.toPlainText(render(touch.bodyMarkdown(), tags))
                    + "\n\n" + touch.cta() + ":\n" + tags.get("payUrl");
            emailService.sendPlatformCampaignEmail(
                    toEmail.trim(), render(touch.subject(), tags), text, html, FROM_DISPLAY_NAME);
            return new DeliveryResult(true, false, null);
        } catch (Exception ex) {
            log.warn("Domain offer test email failed to {}: {}", toEmail, ex.toString());
            return new DeliveryResult(false, false, trim(ex.getMessage()));
        }
    }

    public Map<String, String> tags(DomainOfferOrder order, Business business) {
        String slug = business == null ? null : business.getSlug();
        String businessName = business == null || business.getName() == null || business.getName().isBlank()
                ? slug
                : business.getName();
        String suggested = order.effectiveDomain();
        if (suggested == null || suggested.isBlank()) {
            suggested = audienceService.suggestedDomain(slug);
        }
        String shopUrl = audienceService.shopUrl(business);
        String token = order.getPayToken() == null ? "" : order.getPayToken();
        String payUrl = shopUrl + "/custom-domain?t=" + token;
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("name", firstName(order.getContactName()));
        tags.put("email", order.getContactEmail() == null ? "" : order.getContactEmail());
        tags.put("businessName", businessName == null ? "" : businessName);
        tags.put("currentHost", audienceService.shopHost(slug));
        tags.put("suggestedDomain", suggested);
        tags.put("domain", suggested);
        tags.put("shopUrl", shopUrl);
        tags.put("price", money(order.getAmountCents()));
        tags.put("payUrl", payUrl);
        tags.put("reference", order.getReference() == null ? "" : order.getReference());
        tags.put("slaDue", formatSla(order.getSlaDueAt()));
        tags.put("themeDirection", directionLabel(order.getThemeDirection()));
        return tags;
    }

    public String payUrl(DomainOfferOrder order, Business business) {
        return tags(order, business).get("payUrl");
    }

    public String render(String template, Map<String, String> tags) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher matcher = TAG.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = tags.getOrDefault(matcher.group(1), matcher.group(0));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(out);
        return out.toString().replace("Hi ,", "Hi,");
    }

    private boolean sendEmail(
            String toEmail,
            String subject,
            String bodyMarkdown,
            String cta,
            String ctaUrl,
            String shopUrl,
            String preview,
            DomainOfferOrder order,
            String kind
    ) {
        if (toEmail == null || toEmail.isBlank()) {
            order.setLastEmailError("missing_email");
            return false;
        }
        if (!emailService.canDeliverEmail()) {
            order.setLastEmailError("email_not_configured");
            return false;
        }
        try {
            String html = emailRenderer.renderHtml(
                    subject,
                    PlatformEmailMarkdown.toHtml(bodyMarkdown),
                    cta,
                    ctaUrl,
                    shopUrl,
                    preview);
            String text = PlatformEmailMarkdown.toPlainText(bodyMarkdown)
                    + "\n\n" + cta + ":\n" + ctaUrl;
            emailService.sendPlatformCampaignEmail(toEmail, subject, text, html, FROM_DISPLAY_NAME);
            order.setLastEmailError(null);
            log.info("Domain offer {} email sent order={} to={}", kind, order.getId(), toEmail);
            return true;
        } catch (Exception ex) {
            order.setLastEmailError(trim(ex.getMessage()));
            log.warn("Domain offer {} email failed order={} to={}: {}",
                    kind, order.getId(), toEmail, ex.toString());
            return false;
        }
    }

    private boolean sendInApp(
            DomainOfferOrder order,
            String title,
            String body,
            String actionUrl,
            String kind
    ) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("title", title);
            payload.put("body", body);
            payload.put("actionUrl", actionUrl);
            payload.put("offerReference", order.getReference());
            payload.put("priceCents", order.getAmountCents());
            String json = objectMapper.writeValueAsString(payload);
            String dedupe = "domain_offer:" + order.getId() + ":" + kind;
            inAppService.tryInsertDedupe(
                    order.getBusinessId(),
                    "domain.offer",
                    dedupe,
                    "promotional",
                    "LOW",
                    json);
            return true;
        } catch (Exception ex) {
            log.warn("Domain offer in-app failed order={}: {}", order.getId(), ex.toString());
            return false;
        }
    }

    private static String directionLabel(String raw) {
        DomainOfferThemeDirection direction = DomainOfferThemeDirection.fromWire(raw);
        return direction == null ? DomainOfferThemeDirection.DESIGNER_CHOICE.label() : direction.label();
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return "there";
        }
        return name.trim().split("\\s+")[0];
    }

    private static String money(long cents) {
        BigDecimal amount = BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        NumberFormat format = NumberFormat.getNumberInstance(Locale.US);
        format.setMaximumFractionDigits(0);
        return "KES " + format.format(amount);
    }

    private static String formatSla(Instant slaDueAt) {
        if (slaDueAt == null) {
            return "within 24 hours";
        }
        return SLA_FORMAT.format(slaDueAt);
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return "send_failed";
        }
        return value.length() > 480 ? value.substring(0, 480) : value;
    }

    public static ZoneId nairobi() {
        return NAIROBI;
    }
}
