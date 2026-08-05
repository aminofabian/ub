package zelisline.ub.messaging.application;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.messaging.infrastructure.MetaWhatsAppMessagingClient;
import zelisline.ub.messaging.infrastructure.RapidApiWhatsAppLookupClient;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;

/**
 * Shared WhatsApp/SMS delivery helper used by credit sale receipts and balance reminders.
 * Performs RapidAPI WhatsApp lookup, then Meta WhatsApp, then SMS fallback.
 *
 * <p>Credit sale receipts prefer free-form itemized text (24h window), then the approved
 * {@code credit_sale_receipt} template for cold outreach. Balance reminders use
 * {@code payment_reminder}. Free-form text is also used for SMS fallback.
 */
@Component
@RequiredArgsConstructor
public class CustomerMessageDispatcher {

    /** Approved Meta utility template: Hello {{1}} … balance {{2}} … link {{3}}. */
    public static final String PAYMENT_REMINDER_TEMPLATE = "payment_reminder";
    /** Prefer en_US — Meta Business Manager registers most EN utility templates this way. */
    public static final String PAYMENT_REMINDER_LANGUAGE = "en_US";

    /**
     * Meta utility template for new credit purchases:
     * Hello {{1}} … at {{2}}. Items: {{3}}. Sale {{4}}, tab {{5}}. Link {{6}}.
     */
    public static final String CREDIT_SALE_RECEIPT_TEMPLATE = "credit_sale_receipt";
    public static final String CREDIT_SALE_RECEIPT_LANGUAGE = "en_US";

    private final RapidApiWhatsAppLookupClient whatsAppLookupClient;
    private final MetaWhatsAppMessagingClient metaWhatsAppClient;
    private final SmsMessagingClient smsMessagingClient;

    /**
     * Free-form WhatsApp text (24h window only) with SMS fallback.
     * Always attempts WhatsApp first; RapidAPI "not on WhatsApp" does not skip Meta.
     */
    public DeliveryResult deliver(TenantMessagingConfig messaging, String phoneDigits, String message) {
        String e164 = "+" + phoneDigits;
        var lookup = whatsAppLookupClient.lookup(messaging, e164);
        return attemptWhatsAppTextThenSms(messaging, phoneDigits, e164, message, lookup);
    }

    /**
     * Credit-sale receipt delivery.
     *
     * <p>Order is deliberately cold-safe: approved {@code payment_reminder} template first
     * (reaches every WhatsApp number), then optional {@code credit_sale_receipt}, then
     * free-form text (24h session only), then SMS. Free-form-first was dropping cold
     * numbers when the newer receipt template was missing/unapproved.
     *
     * @param receiptParams template body vars: name, shop, items, sale total, tab total, pay URL
     * @param itemizedMessage free-form / SMS body
     */
    public DeliveryResult deliverCreditSaleReceipt(
            TenantMessagingConfig messaging,
            String phoneDigits,
            List<String> receiptParams,
            String itemizedMessage
    ) {
        String e164 = "+" + phoneDigits;
        var lookup = whatsAppLookupClient.lookup(messaging, e164);
        // Always try WhatsApp (templates reach cold numbers). Do not skip on RapidAPI
        // "not on WhatsApp" — that false-negative was silencing cold sends.
        return attemptCreditSaleWhatsAppThenSms(
                messaging, phoneDigits, e164, receiptParams, itemizedMessage, lookup);
    }

    /**
     * Balance/payment reminder: Meta WhatsApp via approved template (works cold), SMS free-form fallback.
     *
     * @param bodyParams template body vars in order: name, balance text, payment URL
     * @param smsMessage free-form text used only if WhatsApp fails / not available
     */
    public DeliveryResult deliverPaymentReminder(
            TenantMessagingConfig messaging,
            String phoneDigits,
            List<String> bodyParams,
            String smsMessage
    ) {
        String e164 = "+" + phoneDigits;
        var lookup = whatsAppLookupClient.lookup(messaging, e164);
        // Template works cold — do not skip WhatsApp when RapidAPI says offline.
        return attemptWhatsAppTemplateThenSms(
                messaging,
                phoneDigits,
                e164,
                PAYMENT_REMINDER_TEMPLATE,
                PAYMENT_REMINDER_LANGUAGE,
                bodyParams,
                smsMessage,
                lookup);
    }

    /**
     * Meta WhatsApp {@code payment_reminder} template, then SMS fallback when WhatsApp fails.
     */
    public DeliveryResult deliverPaymentReminderDirect(
            TenantMessagingConfig messaging,
            String phoneDigits,
            List<String> bodyParams
    ) {
        DeliveryResult wa = deliverTemplateDirect(
                messaging, phoneDigits, PAYMENT_REMINDER_TEMPLATE, PAYMENT_REMINDER_LANGUAGE, bodyParams);
        if ("sent".equals(wa.outcome())) {
            return wa;
        }
        return withSmsFallback(messaging, phoneDigits, paymentReminderPreview(bodyParams), wa);
    }

    public DeliveryResult deliverTemplateDirect(
            TenantMessagingConfig messaging,
            String phoneDigits,
            String templateName,
            String languageCode,
            List<String> bodyParams
    ) {
        var lookup = RapidApiWhatsAppLookupClient.LookupResult.lookupSkipped("whatsapp_only_test");
        if (!messaging.metaWhatsAppConfigured()) {
            return new DeliveryResult(
                    lookup,
                    "whatsapp",
                    "skipped",
                    "Meta WhatsApp is not configured (phone number ID + access token).");
        }
        var send = metaWhatsAppClient.sendTemplate(
                messaging, phoneDigits, templateName, languageCode, bodyParams);
        if (send.sent()) {
            return new DeliveryResult(lookup, send.channel(), "sent", "template:" + templateName);
        }
        if (send.authFailure()) {
            return new DeliveryResult(
                    lookup,
                    "whatsapp",
                    "failed",
                    "whatsapp_failed:" + send.detail()
                            + " [source=" + nullToNone(messaging.metaAccessTokenSource())
                            + " token=" + messaging.metaAccessTokenFingerprint()
                            + " phone_id=" + nullToNone(messaging.metaPhoneNumberId())
                            + "]. If source=tenant, clear the Credit tab Meta token."
                            + " If source=env, remove WHATSAPP_META_ACCESS_TOKEN from the server."
                            + " If source=platform, paste a fresh permanent System User token in"
                            + " Super Admin → Platform integrations (must match this phone number ID).");
        }
        String prefix = send.skipped() ? "whatsapp_skipped:" : "whatsapp_failed:";
        return new DeliveryResult(lookup, "whatsapp", "failed", prefix + send.detail());
    }

    /**
     * Free-form Meta WhatsApp text (24h window), then SMS fallback when WhatsApp fails.
     */
    public DeliveryResult deliverDirect(TenantMessagingConfig messaging, String phoneDigits, String message) {
        var lookup = RapidApiWhatsAppLookupClient.LookupResult.lookupSkipped("whatsapp_only_test");
        if (!messaging.metaWhatsAppConfigured()) {
            DeliveryResult skipped = new DeliveryResult(
                    lookup,
                    "whatsapp",
                    "skipped",
                    "Meta WhatsApp is not configured (phone number ID + access token).");
            return withSmsFallback(messaging, phoneDigits, message, skipped);
        }
        var send = metaWhatsAppClient.sendText(messaging, phoneDigits, message);
        if (send.sent()) {
            return new DeliveryResult(lookup, send.channel(), "sent", send.detail());
        }
        DeliveryResult failed;
        if (send.authFailure()) {
            failed = new DeliveryResult(
                    lookup,
                    "whatsapp",
                    "failed",
                    "whatsapp_failed:" + send.detail()
                            + " [source=" + nullToNone(messaging.metaAccessTokenSource())
                            + " token=" + messaging.metaAccessTokenFingerprint()
                            + " phone_id=" + nullToNone(messaging.metaPhoneNumberId())
                            + "]. If source=tenant, clear the Credit tab Meta token."
                            + " If source=env, remove WHATSAPP_META_ACCESS_TOKEN from the server."
                            + " If source=platform, paste a fresh permanent System User token in"
                            + " Super Admin → Platform integrations (must match this phone number ID).");
        } else {
            String prefix = send.skipped() ? "whatsapp_skipped:" : "whatsapp_failed:";
            failed = new DeliveryResult(lookup, "whatsapp", "failed", prefix + send.detail());
        }
        return withSmsFallback(messaging, phoneDigits, message, failed);
    }

    /**
     * After a WhatsApp miss, try SMS with the same (or preview) body.
     */
    private DeliveryResult withSmsFallback(
            TenantMessagingConfig messaging,
            String phoneDigits,
            String smsMessage,
            DeliveryResult prior
    ) {
        if (!messaging.smsConfigured()) {
            return prior;
        }
        String e164 = "+" + phoneDigits;
        var sms = smsMessagingClient.sendText(messaging, e164, smsMessage);
        String waDetail = prior.detail() != null ? prior.detail() : "whatsapp_failed";
        if (sms.sent() || sms.stub()) {
            return new DeliveryResult(
                    prior.lookup(),
                    sms.channel(),
                    sms.sent() ? "sent" : "stub",
                    waDetail + "; sms:" + sms.detail());
        }
        return new DeliveryResult(
                prior.lookup(),
                prior.channel() != null ? prior.channel() : "whatsapp",
                "failed",
                waDetail + "; sms_failed:" + sms.detail());
    }

    /**
     * SMS-only send (no RapidAPI lookup, no Meta WhatsApp). Used for the standalone SMS admin test.
     */
    public DeliveryResult deliverSmsOnly(TenantMessagingConfig messaging, String phoneDigits, String message) {
        String e164 = "+" + phoneDigits;
        var lookup = RapidApiWhatsAppLookupClient.LookupResult.lookupSkipped("sms_only_test");
        if (!messaging.smsConfigured()) {
            return new DeliveryResult(
                    lookup,
                    "sms",
                    "skipped",
                    "SMS provider is not configured (set Sozuri or Africa's Talking in admin).");
        }
        var sms = smsMessagingClient.sendText(messaging, e164, message);
        String channel = sms.channel();
        String outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
        return new DeliveryResult(lookup, channel, outcome, sms.detail());
    }

    /**
     * OTP delivery: SMS first (reliable), then WhatsApp as a bonus.
     *
     * <p>Free-form WhatsApp alone must not count as success — Meta often accepts the API call
     * outside the 24h window without the customer ever seeing the code. When SMS is configured,
     * SMS must succeed (or stub). When SMS is not configured, the send fails with a clear error
     * instead of returning a false WhatsApp "sent".
     */
    public DeliveryResult deliverBothChannels(
            TenantMessagingConfig messaging,
            String phoneDigits,
            String message
    ) {
        String e164 = "+" + phoneDigits;
        boolean waConfigured = messaging.metaWhatsAppConfigured();
        boolean smsConfigured = messaging.smsConfigured();
        var lookup = RapidApiWhatsAppLookupClient.LookupResult.lookupSkipped("otp_both_channels");
        if (!waConfigured && !smsConfigured) {
            return new DeliveryResult(
                    lookup,
                    "none",
                    "failed",
                    "WhatsApp and SMS are not configured");
        }
        if (!smsConfigured) {
            return new DeliveryResult(
                    lookup,
                    "none",
                    "failed",
                    "SMS is required for verification codes. Configure Sozuri or TextSMS under"
                            + " Super Admin → Platform integrations. Free-form WhatsApp alone is"
                            + " unreliable outside Meta's 24h window.");
        }

        boolean waOk = false;
        boolean smsOk = false;
        StringBuilder detail = new StringBuilder();

        // SMS first — this is what the customer can actually read for OTPs.
        var sms = smsMessagingClient.sendText(messaging, e164, message);
        smsOk = sms.sent() || sms.stub();
        if (smsOk) {
            detail.append(sms.channel()).append(':').append(sms.sent() ? "sent" : "stub");
        } else {
            detail.append("sms_failed:").append(sms.detail());
        }

        if (waConfigured) {
            var send = metaWhatsAppClient.sendText(messaging, phoneDigits, message);
            waOk = send.sent();
            detail.append("; ");
            if (waOk) {
                detail.append("whatsapp:sent");
            } else {
                String prefix = send.skipped() ? "whatsapp_skipped:" : "whatsapp_failed:";
                detail.append(prefix).append(send.detail());
            }
        }

        if (!smsOk) {
            // Do not report WhatsApp-only success for OTPs.
            return new DeliveryResult(
                    lookup,
                    channelLabel(waOk, false),
                    "failed",
                    detail.toString());
        }

        return new DeliveryResult(
                lookup,
                channelLabel(waOk, true),
                sms.sent() ? "sent" : "stub",
                detail.toString());
    }

    private static String channelLabel(boolean whatsapp, boolean sms) {
        if (whatsapp && sms) {
            return "whatsapp+sms";
        }
        if (whatsapp) {
            return "whatsapp";
        }
        if (sms) {
            return "sms";
        }
        return "none";
    }

    private DeliveryResult attemptCreditSaleWhatsAppThenSms(
            TenantMessagingConfig messaging,
            String phoneDigits,
            String e164,
            List<String> receiptParams,
            String itemizedMessage,
            RapidApiWhatsAppLookupClient.LookupResult lookup
    ) {
        StringBuilder waTrail = new StringBuilder();

        // 1) Known-good cold template first — reaches numbers that have never messaged the biz.
        List<String> paymentReminderParams = paymentReminderParamsFromReceipt(receiptParams);
        var paymentReminderSend = metaWhatsAppClient.sendTemplate(
                messaging,
                phoneDigits,
                PAYMENT_REMINDER_TEMPLATE,
                PAYMENT_REMINDER_LANGUAGE,
                paymentReminderParams);
        if (paymentReminderSend.sent()) {
            return new DeliveryResult(
                    lookup, paymentReminderSend.channel(), "sent", "template:" + PAYMENT_REMINDER_TEMPLATE);
        }
        appendWaFailure(waTrail, paymentReminderSend, "payment_reminder");
        if (paymentReminderSend.authFailure()) {
            return failWhatsAppOrSms(messaging, e164, itemizedMessage, lookup, waTrail.toString(), true);
        }
        if (paymentReminderSend.templatePaused()) {
            return failWhatsAppOrSms(
                    messaging,
                    e164,
                    itemizedMessage,
                    lookup,
                    waTrail + " (template paused by Meta — usually 3h/6h; use SMS until Active)",
                    false);
        }

        // 2) Richer receipt template when approved in Meta.
        var receiptSend = metaWhatsAppClient.sendTemplate(
                messaging,
                phoneDigits,
                CREDIT_SALE_RECEIPT_TEMPLATE,
                CREDIT_SALE_RECEIPT_LANGUAGE,
                receiptParams);
        if (receiptSend.sent()) {
            return new DeliveryResult(
                    lookup, receiptSend.channel(), "sent", "template:" + CREDIT_SALE_RECEIPT_TEMPLATE);
        }
        appendWaFailure(waTrail, receiptSend, "credit_sale_receipt");
        if (receiptSend.authFailure()) {
            return failWhatsAppOrSms(messaging, e164, itemizedMessage, lookup, waTrail.toString(), true);
        }
        if (receiptSend.templatePaused()) {
            return failWhatsAppOrSms(
                    messaging,
                    e164,
                    itemizedMessage,
                    lookup,
                    waTrail + " (template paused by Meta — usually 3h/6h; use SMS until Active)",
                    false);
        }

        // 3) Free-form only helps inside the 24h session window.
        var textSend = metaWhatsAppClient.sendText(messaging, phoneDigits, itemizedMessage);
        if (textSend.sent()) {
            return new DeliveryResult(lookup, textSend.channel(), "sent", "text:itemized");
        }
        appendWaFailure(waTrail, textSend, "text");

        return failWhatsAppOrSms(
                messaging,
                e164,
                itemizedMessage,
                lookup,
                waTrail.toString(),
                textSend.authFailure());
    }

    /** Map receipt params → payment_reminder {{name}}, {{balance}}, {{link}}. */
    private static List<String> paymentReminderParamsFromReceipt(List<String> receiptParams) {
        String name = paramAt(receiptParams, 0, "there");
        String balance = paramAt(receiptParams, 4, "-");
        String link = paramAt(receiptParams, 5, "-");
        return paymentReminderBodyParams(name, balance, link);
    }

    private static String paramAt(List<String> params, int index, String fallback) {
        if (params == null || index < 0 || index >= params.size()) {
            return fallback;
        }
        String value = params.get(index);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private DeliveryResult attemptWhatsAppTemplateThenSms(
            TenantMessagingConfig messaging,
            String phoneDigits,
            String e164,
            String templateName,
            String languageCode,
            List<String> bodyParams,
            String smsMessage,
            RapidApiWhatsAppLookupClient.LookupResult lookup
    ) {
        var send = metaWhatsAppClient.sendTemplate(
                messaging, phoneDigits, templateName, languageCode, bodyParams);
        if (send.sent()) {
            return new DeliveryResult(lookup, send.channel(), "sent", "template:" + templateName);
        }

        String waPrefix = send.skipped() ? "whatsapp_skipped:" : "whatsapp_failed:";
        String waDetail = waPrefix + send.detail();
        if (send.authFailure()) {
            waDetail += " (Meta access token rejected)";
        }
        return failWhatsAppOrSms(messaging, e164, smsMessage, lookup, waDetail, send.authFailure());
    }

    private DeliveryResult attemptWhatsAppTextThenSms(
            TenantMessagingConfig messaging,
            String phoneDigits,
            String e164,
            String message,
            RapidApiWhatsAppLookupClient.LookupResult lookup
    ) {
        var send = metaWhatsAppClient.sendText(messaging, phoneDigits, message);
        if (send.sent()) {
            return new DeliveryResult(lookup, send.channel(), "sent", send.detail());
        }

        String waPrefix = send.skipped() ? "whatsapp_skipped:" : "whatsapp_failed:";
        String waDetail = waPrefix + send.detail();
        if (send.authFailure()) {
            waDetail += " (Meta access token rejected)";
        }
        return failWhatsAppOrSms(messaging, e164, message, lookup, waDetail, send.authFailure());
    }

    private DeliveryResult failWhatsAppOrSms(
            TenantMessagingConfig messaging,
            String e164,
            String smsMessage,
            RapidApiWhatsAppLookupClient.LookupResult lookup,
            String waDetail,
            boolean authFailure
    ) {
        if (!messaging.smsConfigured()) {
            return new DeliveryResult(
                    lookup,
                    "whatsapp",
                    "failed",
                    waDetail
                            + (authFailure
                                    ? ". SMS fallback is not configured — fix the Meta token or enable Sozuri / Africa's Talking."
                                    : ". SMS fallback is not configured."));
        }

        var sms = smsMessagingClient.sendText(messaging, e164, smsMessage);
        String channel = sms.sent() || sms.stub() ? sms.channel() : "sms";
        String outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
        String detail = waDetail + "; sms:" + sms.detail();
        return new DeliveryResult(lookup, channel, outcome, detail);
    }

    private static void appendWaFailure(
            StringBuilder trail,
            MetaWhatsAppMessagingClient.SendResult send,
            String kind
    ) {
        if (trail.length() > 0) {
            trail.append("; ");
        }
        String prefix = send.skipped() ? "whatsapp_skipped:" : "whatsapp_failed:";
        trail.append(prefix).append(kind).append(':').append(send.detail());
        if (send.authFailure()) {
            trail.append(" (Meta access token rejected)");
        }
    }

    public record DeliveryResult(
            RapidApiWhatsAppLookupClient.LookupResult lookup,
            String channel,
            String outcome,
            String detail
    ) {
    }

    private static String nullToNone(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        return value.trim();
    }

    /** Body vars for Meta {@code payment_reminder}: {{1}} name, {{2}} balance, {{3}} pay link. */
    public static List<String> paymentReminderBodyParams(String customerName, String balanceText, String paymentUrl) {
        String name = (customerName == null || customerName.isBlank()) ? "there" : customerName.trim();
        return List.of(
                name,
                balanceText == null || balanceText.isBlank() ? "-" : balanceText.trim(),
                paymentUrl == null || paymentUrl.isBlank() ? "-" : paymentUrl.trim());
    }

    /** Preview of the approved template filled with the given body params (for admin UI). */
    public static String paymentReminderPreview(List<String> bodyParams) {
        String name = bodyParams != null && bodyParams.size() > 0 ? bodyParams.get(0) : "there";
        String balance = bodyParams != null && bodyParams.size() > 1 ? bodyParams.get(1) : "-";
        String link = bodyParams != null && bodyParams.size() > 2 ? bodyParams.get(2) : "-";
        return "Hello " + name
                + ", this is a reminder that you still have an outstanding balance of "
                + balance
                + " at Palmart Fresh Foods & Butchery. Please complete your payment using this secure link: "
                + link
                + ". Thank you.";
    }

    /**
     * Body vars for Meta {@code credit_sale_receipt}:
     * {{1}} name, {{2}} shop, {{3}} items, {{4}} sale total, {{5}} tab total, {{6}} pay link.
     */
    public static List<String> creditSaleReceiptBodyParams(
            String customerName,
            String shopName,
            String itemsSummary,
            String saleTotal,
            String tabTotal,
            String paymentUrl
    ) {
        String name = (customerName == null || customerName.isBlank()) ? "there" : customerName.trim();
        String shop = (shopName == null || shopName.isBlank()) ? "our shop" : shopName.trim();
        return List.of(
                name,
                shop,
                itemsSummary == null || itemsSummary.isBlank() ? "items on credit" : itemsSummary.trim(),
                saleTotal == null || saleTotal.isBlank() ? "-" : saleTotal.trim(),
                tabTotal == null || tabTotal.isBlank() ? "-" : tabTotal.trim(),
                paymentUrl == null || paymentUrl.isBlank() ? "-" : paymentUrl.trim());
    }

    public static String creditSaleReceiptPreview(List<String> bodyParams) {
        String name = param(bodyParams, 0, "there");
        String shop = param(bodyParams, 1, "our shop");
        String items = param(bodyParams, 2, "items on credit");
        String sale = param(bodyParams, 3, "-");
        String tab = param(bodyParams, 4, "-");
        String link = param(bodyParams, 5, "-");
        return "Hello " + name
                + ", thank you for your credit purchase at " + shop
                + ". Items on this sale: " + items
                + ". This sale total is " + sale
                + " and your outstanding tab balance is now " + tab
                + ". Please complete your payment using this secure link: " + link
                + ". Thank you for shopping with us.";
    }

    private static String param(List<String> bodyParams, int index, String fallback) {
        if (bodyParams == null || bodyParams.size() <= index) {
            return fallback;
        }
        String value = bodyParams.get(index);
        return value == null || value.isBlank() ? fallback : value;
    }
}
