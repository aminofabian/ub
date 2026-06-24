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
        String channel;
        String outcome;
        String detail;

        var lookup = whatsAppLookupClient.lookup(messaging, e164);
        if (lookup.onWhatsApp()) {
            var send = metaWhatsAppClient.sendText(messaging, phoneDigits, message);
            if (send.sent()) {
                channel = send.channel();
                outcome = "sent";
                detail = send.detail();
            } else if (send.skipped()) {
                var sms = smsMessagingClient.sendText(messaging, e164, message);
                channel = sms.channel();
                outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
                detail = "whatsapp_skipped:" + send.detail() + ";" + sms.detail();
            } else {
                var sms = smsMessagingClient.sendText(messaging, e164, message);
                channel = sms.sent() || sms.stub() ? sms.channel() : "sms";
                outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
                detail = "whatsapp_failed:" + send.detail() + ";" + sms.detail();
            }
        } else if (lookup.skipped()) {
            var sms = smsMessagingClient.sendText(messaging, e164, message);
            channel = sms.channel();
            outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
            detail = "lookup_skipped:" + lookup.detail() + ";" + sms.detail();
        } else {
            var sms = smsMessagingClient.sendText(messaging, e164, message);
            channel = sms.channel();
            outcome = sms.sent() ? "sent" : (sms.stub() ? "stub" : "failed");
            detail = "not_on_whatsapp:" + lookup.detail() + ";" + sms.detail();
        }
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
