package zelisline.ub.notifications.infrastructure;

import java.security.GeneralSecurityException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import nl.martijndwars.webpush.Subscription;
import zelisline.ub.notifications.config.WebPushProperties;
import zelisline.ub.notifications.domain.DeviceToken;

@Component
@RequiredArgsConstructor
public class WebPushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    private final WebPushProperties webPushProperties;
    private final ObjectMapper objectMapper;

    public SendResult send(DeviceToken token, String title, String body, String url) {
        if (!webPushProperties.configured()) {
            return SendResult.skipped("web_push_not_configured");
        }
        if (token.getEndpoint() == null || token.getP256dh() == null || token.getAuthSecret() == null) {
            return SendResult.failed("invalid_subscription");
        }
        try {
            PushService pushService = new PushService(
                    webPushProperties.vapidPublicKey(),
                    webPushProperties.vapidPrivateKey(),
                    webPushProperties.subject());
            Subscription subscription = new Subscription(
                    token.getEndpoint(),
                    new Subscription.Keys(token.getP256dh(), token.getAuthSecret()));
            String payload = objectMapper.writeValueAsString(new PushPayload(
                    title != null ? title : "Palmart",
                    body != null ? body : "",
                    url != null ? url : "/"));
            Notification notification = new Notification(subscription, payload);
            pushService.send(notification);
            return SendResult.sent("vapid");
        } catch (GeneralSecurityException ex) {
            log.warn("Web push crypto error tokenId={}: {}", token.getId(), ex.getMessage());
            return SendResult.failed("crypto_error");
        } catch (Exception ex) {
            log.warn("Web push send failed tokenId={}: {}", token.getId(), ex.getMessage());
            return SendResult.failed("send_error");
        }
    }

    public int sendToTokens(List<DeviceToken> tokens, String title, String body, String url) {
        int sent = 0;
        for (DeviceToken token : tokens) {
            if (!DeviceToken.PLATFORM_WEB.equals(token.getPlatform())) {
                continue;
            }
            SendResult result = send(token, title, body, url);
            if (result.sent()) {
                sent++;
            }
        }
        return sent;
    }

    public record PushPayload(String title, String body, String url) {
    }

    public record SendResult(boolean sent, boolean skipped, String detail) {
        public static SendResult sent(String detail) {
            return new SendResult(true, false, detail);
        }

        public static SendResult skipped(String detail) {
            return new SendResult(false, true, detail);
        }

        public static SendResult failed(String detail) {
            return new SendResult(false, false, detail);
        }
    }
}
