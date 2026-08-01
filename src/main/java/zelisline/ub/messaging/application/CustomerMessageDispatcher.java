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
 * <p>WhatsApp cold outreach (outside the 24h customer-service window) uses the approved
 * {@code payment_reminder} template. Free-form text is only used for SMS fallback.
 */
@Component
@RequiredArgsConstructor
public class CustomerMessageDispatcher {

    /** Approved Meta utility template: Hello {{1}} … balance {{2}} … link {{3}}. */
    public static final String PAYMENT_REMINDER_TEMPLATE = "payment_reminder";
    public static final String PAYMENT_REMINDER_LANGUAGE = "en";

    private final RapidApiWhatsAppLookupClient whatsAppLookupClient;
    private final MetaWhatsAppMessagingClient metaWhatsAppClient;
    private final SmsMessagingClient smsMessagingClient;

    /**
     * Free-form WhatsApp text (24h window only) with SMS fallback.
     * Prefer {@link #deliverPaymentReminder} for credit/balance reminders.
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
            return attemptWhatsAppTemplateThenSms(messaging, phoneDigits, e164, bodyParams, smsMessage, lookup);
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
        String e164 = "+" + phoneDigits;
        var lookup = RapidApiWhatsAppLookupClient.LookupResult.lookupSkipped("whatsapp_only_test");
        if (!messaging.metaWhatsAppConfigured()) {
            return new DeliveryResult(
                    lookup,
                    "whatsapp",
                    "skipped",
                    "Meta WhatsApp is not configured (phone number ID + access token).");
        }
        var send = metaWhatsAppClient.sendTemplate(
                messaging,
                phoneDigits,
                PAYMENT_REMINDER_TEMPLATE,
                PAYMENT_REMINDER_LANGUAGE,
                bodyParams);
        if (send.sent()) {
            return new DeliveryResult(lookup, send.channel(), "sent", "template:" + PAYMENT_REMINDER_TEMPLATE);
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
        String e164 = "+" + phoneDigits;
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

    private DeliveryResult attemptWhatsAppTemplateThenSms(
            TenantMessagingConfig messaging,
            String phoneDigits,
            String e164,
            List<String> bodyParams,
            String smsMessage,
            RapidApiWhatsAppLookupClient.LookupResult lookup
    ) {
        var send = metaWhatsAppClient.sendTemplate(
                messaging,
                phoneDigits,
                PAYMENT_REMINDER_TEMPLATE,
                PAYMENT_REMINDER_LANGUAGE,
                bodyParams);
        if (send.sent()) {
            return new DeliveryResult(lookup, send.channel(), "sent", "template:" + PAYMENT_REMINDER_TEMPLATE);
        }

        String waPrefix = send.skipped() ? "whatsapp_skipped:" : "whatsapp_failed:";
        String waDetail = waPrefix + send.detail();
        if (send.authFailure()) {
            waDetail += " (Meta access token rejected)";
        }

        if (!messaging.smsConfigured()) {
            return new DeliveryResult(
                    lookup,
                    "whatsapp",
                    "failed",
                    waDetail
                            + (send.authFailure()
                                    ? ". SMS fallback is not configured — fix the Meta token or enable Sozuri / Africa's Talking."
                                    : ". SMS fallback is not configured."));
        }

        var sms = smsMessagingClient.sendText(messaging, e164, smsMessage);
        String channel = sms.sent() || sms.stub() ? sms.channel() : "sms";
        String outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
        String detail = waDetail + "; sms:" + sms.detail();
        return new DeliveryResult(lookup, channel, outcome, detail);
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

        if (!messaging.smsConfigured()) {
            return new DeliveryResult(
                    lookup,
                    "whatsapp",
                    "failed",
                    waDetail
                            + (send.authFailure()
                                    ? ". SMS fallback is not configured — fix the Meta token or enable Sozuri / Africa's Talking."
                                    : ". SMS fallback is not configured."));
        }

        var sms = smsMessagingClient.sendText(messaging, e164, message);
        String channel = sms.sent() || sms.stub() ? sms.channel() : "sms";
        String outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
        String detail = waDetail + "; sms:" + sms.detail();
        return new DeliveryResult(lookup, channel, outcome, detail);
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
}
