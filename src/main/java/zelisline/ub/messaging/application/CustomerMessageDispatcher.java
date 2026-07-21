package zelisline.ub.messaging.application;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.messaging.infrastructure.MetaWhatsAppMessagingClient;
import zelisline.ub.messaging.infrastructure.RapidApiWhatsAppLookupClient;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;

/**
 * Shared WhatsApp/SMS delivery helper used by credit sale receipts and balance reminders.
 * Performs RapidAPI WhatsApp lookup, then Meta WhatsApp, then SMS fallback.
 */
@Component
@RequiredArgsConstructor
public class CustomerMessageDispatcher {

    private final RapidApiWhatsAppLookupClient whatsAppLookupClient;
    private final MetaWhatsAppMessagingClient metaWhatsAppClient;
    private final SmsMessagingClient smsMessagingClient;

    /**
     * @param phoneDigits MSISDN without leading + (e.g. 254712345678)
     */
    public DeliveryResult deliver(TenantMessagingConfig messaging, String phoneDigits, String message) {
        String e164 = "+" + phoneDigits;
        var lookup = whatsAppLookupClient.lookup(messaging, e164);

        // Attempt WhatsApp unless the lookup is a *definitive* "not on WhatsApp".
        // Inconclusive lookups (RapidAPI unconfigured, unrecognized responses, HTTP
        // or parse errors) must not block delivery — Meta rejects genuine non-WhatsApp
        // numbers and we fall back to SMS from there.
        if (lookup.onWhatsApp() || lookup.skipped()) {
            return attemptWhatsAppThenSms(messaging, phoneDigits, e164, message, lookup);
        }

        var sms = smsMessagingClient.sendText(messaging, e164, message);
        String channel = sms.channel();
        String outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
        String detail = "not_on_whatsapp:" + lookup.detail() + ";" + sms.detail();
        return new DeliveryResult(lookup, channel, outcome, detail);
    }

    /**
     * Attempts Meta WhatsApp directly (no RapidAPI lookup, no SMS fallback).
     * Used for the standalone WhatsApp admin test.
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
                            + ". Paste a fresh permanent token from Meta Business → WhatsApp → API setup, save, then retry.");
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

    private DeliveryResult attemptWhatsAppThenSms(
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

        // Any Meta failure (including 401 auth) falls through to SMS when configured.
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
}
