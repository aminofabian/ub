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
    public static final String PAYMENT_REMINDER_LANGUAGE = "en";

    /**
     * Meta utility template for new credit purchases:
     * Hello {{1}} … at {{2}}. Items: {{3}}. Sale {{4}}, tab {{5}}. Link {{6}}.
     */
    public static final String CREDIT_SALE_RECEIPT_TEMPLATE = "credit_sale_receipt";
    public static final String CREDIT_SALE_RECEIPT_LANGUAGE = "en";

    private final RapidApiWhatsAppLookupClient whatsAppLookupClient;
    private final MetaWhatsAppMessagingClient metaWhatsAppClient;
    private final SmsMessagingClient smsMessagingClient;

    /**
     * Free-form WhatsApp text (24h window only) with SMS fallback.
     */
    public DeliveryResult deliver(TenantMessagingConfig messaging, String phoneDigits, String message) {
        String e164 = "+" + phoneDigits;
        var lookup = whatsAppLookupClient.lookup(messaging, e164);

        if (lookup.onWhatsApp() || lookup.skipped()) {
            return attemptWhatsAppTextThenSms(messaging, phoneDigits, e164, message, lookup);
        }

        var sms = smsMessagingClient.sendText(messaging, e164, message);
        String channel = sms.channel();
        String outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
        String detail = "not_on_whatsapp:" + lookup.detail() + ";" + sms.detail();
        return new DeliveryResult(lookup, channel, outcome, detail);
    }

    /**
     * New credit-sale receipt: itemized free-form WhatsApp first (inside 24h window),
     * then {@code credit_sale_receipt} template for cold numbers, then SMS with the same
     * itemized text.
     *
     * @param receiptParams template body vars: name, shop, items, sale total, tab total, pay URL
     * @param itemizedMessage free-form body used for in-window WhatsApp and SMS fallback
     */
    public DeliveryResult deliverCreditSaleReceipt(
            TenantMessagingConfig messaging,
            String phoneDigits,
            List<String> receiptParams,
            String itemizedMessage
    ) {
        String e164 = "+" + phoneDigits;
        var lookup = whatsAppLookupClient.lookup(messaging, e164);

        if (lookup.onWhatsApp() || lookup.skipped()) {
            return attemptCreditSaleWhatsAppThenSms(
                    messaging, phoneDigits, e164, receiptParams, itemizedMessage, lookup);
        }

        var sms = smsMessagingClient.sendText(messaging, e164, itemizedMessage);
        String channel = sms.channel();
        String outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
        String detail = "not_on_whatsapp:" + lookup.detail() + ";" + sms.detail();
        return new DeliveryResult(lookup, channel, outcome, detail);
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

        if (lookup.onWhatsApp() || lookup.skipped()) {
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

        var sms = smsMessagingClient.sendText(messaging, e164, smsMessage);
        String channel = sms.channel();
        String outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
        String detail = "not_on_whatsapp:" + lookup.detail() + ";" + sms.detail();
        return new DeliveryResult(lookup, channel, outcome, detail);
    }

    /**
     * Attempts Meta WhatsApp template directly (no RapidAPI lookup, no SMS fallback).
     * Used for WhatsApp-only admin tests and staff "WhatsApp" channel preference.
     */
    public DeliveryResult deliverPaymentReminderDirect(
            TenantMessagingConfig messaging,
            String phoneDigits,
            List<String> bodyParams
    ) {
        return deliverTemplateDirect(
                messaging, phoneDigits, PAYMENT_REMINDER_TEMPLATE, PAYMENT_REMINDER_LANGUAGE, bodyParams);
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
     * Attempts Meta WhatsApp free-form text directly (no RapidAPI lookup, no SMS fallback).
     * Only works inside the 24h customer-service window.
     */
    public DeliveryResult deliverDirect(TenantMessagingConfig messaging, String phoneDigits, String message) {
        var lookup = RapidApiWhatsAppLookupClient.LookupResult.lookupSkipped("whatsapp_only_test");
        if (!messaging.metaWhatsAppConfigured()) {
            return new DeliveryResult(
                    lookup,
                    "whatsapp",
                    "skipped",
                    "Meta WhatsApp is not configured (phone number ID + access token).");
        }
        var send = metaWhatsAppClient.sendText(messaging, phoneDigits, message);
        if (send.sent()) {
            return new DeliveryResult(lookup, send.channel(), "sent", send.detail());
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
     * Sends the same free-form message on every configured channel (WhatsApp and/or SMS).
     * Used for OTP verification so the customer can read the code from either inbox.
     * Succeeds when at least one channel sends (or stubs); fails only if all attempts fail.
     */
    public DeliveryResult deliverBothChannels(
            TenantMessagingConfig messaging,
            String phoneDigits,
            String message
    ) {
        String e164 = "+" + phoneDigits;
        boolean waConfigured = messaging.metaWhatsAppConfigured();
        boolean smsConfigured = messaging.smsConfigured();
        if (!waConfigured && !smsConfigured) {
            var lookup = RapidApiWhatsAppLookupClient.LookupResult.lookupSkipped("no_channels");
            return new DeliveryResult(
                    lookup,
                    "none",
                    "failed",
                    "WhatsApp and SMS are not configured");
        }

        var lookup = RapidApiWhatsAppLookupClient.LookupResult.lookupSkipped("otp_both_channels");
        boolean waOk = false;
        boolean smsOk = false;
        StringBuilder detail = new StringBuilder();

        if (waConfigured) {
            var send = metaWhatsAppClient.sendText(messaging, phoneDigits, message);
            waOk = send.sent();
            if (detail.length() > 0) {
                detail.append("; ");
            }
            if (waOk) {
                detail.append("whatsapp:sent");
            } else {
                String prefix = send.skipped() ? "whatsapp_skipped:" : "whatsapp_failed:";
                detail.append(prefix).append(send.detail());
            }
        }

        if (smsConfigured) {
            var sms = smsMessagingClient.sendText(messaging, e164, message);
            smsOk = sms.sent() || sms.stub();
            if (detail.length() > 0) {
                detail.append("; ");
            }
            if (smsOk) {
                detail.append(sms.channel()).append(':').append(sms.sent() ? "sent" : "stub");
            } else {
                detail.append("sms_failed:").append(sms.detail());
            }
        }

        if (!waOk && !smsOk) {
            return new DeliveryResult(
                    lookup, channelLabel(waConfigured, smsConfigured), "failed", detail.toString());
        }

        return new DeliveryResult(
                lookup,
                channelLabel(waOk, smsOk),
                "sent",
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

        // 1) Itemized free-form text (works inside the 24h customer-care window).
        var textSend = metaWhatsAppClient.sendText(messaging, phoneDigits, itemizedMessage);
        if (textSend.sent()) {
            return new DeliveryResult(lookup, textSend.channel(), "sent", "text:itemized");
        }
        appendWaFailure(waTrail, textSend, "text");
        if (textSend.authFailure()) {
            return failWhatsAppOrSms(messaging, e164, itemizedMessage, lookup, waTrail.toString(), true);
        }

        // 2) Itemized cold-outreach template (works outside the window once Meta-approved).
        var templateSend = metaWhatsAppClient.sendTemplate(
                messaging,
                phoneDigits,
                CREDIT_SALE_RECEIPT_TEMPLATE,
                CREDIT_SALE_RECEIPT_LANGUAGE,
                receiptParams);
        if (templateSend.sent()) {
            return new DeliveryResult(
                    lookup, templateSend.channel(), "sent", "template:" + CREDIT_SALE_RECEIPT_TEMPLATE);
        }
        appendWaFailure(waTrail, templateSend, "template");
        return failWhatsAppOrSms(
                messaging, e164, itemizedMessage, lookup, waTrail.toString(), templateSend.authFailure());
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
