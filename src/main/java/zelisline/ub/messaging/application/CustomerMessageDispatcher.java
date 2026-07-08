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
     * Attempts Meta WhatsApp directly (bypassing the OSINT lookup gate). Used for
     * admin test sends — does not fall back to the SMS log-only stub.
     */
    public DeliveryResult deliverDirect(TenantMessagingConfig messaging, String phoneDigits, String message) {
        String e164 = "+" + phoneDigits;
        var lookup = RapidApiWhatsAppLookupClient.LookupResult.lookupSkipped("direct_send");
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
        if (messaging.smsConfigured()) {
            var sms = smsMessagingClient.sendText(messaging, e164, message);
            String channel = sms.sent() ? sms.channel() : "sms";
            String outcome = sms.sent() ? "sent" : "failed";
            String prefix = send.skipped() ? "whatsapp_skipped:" : "whatsapp_failed:";
            String detail = prefix + send.detail() + ";" + sms.detail();
            return new DeliveryResult(lookup, channel, outcome, detail);
        }
        return new DeliveryResult(
                lookup,
                "whatsapp",
                "failed",
                "whatsapp_failed:" + send.detail()
                        + (send.skipped() ? "" : ". SMS fallback is not configured."));
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
        if (send.authFailure()) {
            return new DeliveryResult(
                    lookup,
                    "whatsapp",
                    "failed",
                    "whatsapp_failed:" + send.detail());
        }
        if (!messaging.smsConfigured()) {
            return new DeliveryResult(
                    lookup,
                    "whatsapp",
                    "failed",
                    "whatsapp_failed:" + send.detail());
        }
        var sms = smsMessagingClient.sendText(messaging, e164, message);
        String channel = sms.sent() || sms.stub() ? sms.channel() : "sms";
        String outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
        String prefix = send.skipped() ? "whatsapp_skipped:" : "whatsapp_failed:";
        String detail = prefix + send.detail() + ";" + sms.detail();
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
