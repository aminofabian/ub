package zelisline.ub.notifications.application;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.infrastructure.MetaWhatsAppMessagingClient;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.domain.DeviceToken;
import zelisline.ub.notifications.domain.Notification;
import zelisline.ub.notifications.domain.NotificationDelivery;
import zelisline.ub.notifications.repository.DeviceTokenRepository;
import zelisline.ub.notifications.repository.NotificationDeliveryRepository;
import zelisline.ub.notifications.repository.NotificationRepository;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.notifications.infrastructure.FcmSender;
import zelisline.ub.notifications.infrastructure.WebPushSender;
import zelisline.ub.payments.application.StkPhoneNormalizer;

@Service
@RequiredArgsConstructor
public class NotificationDeliveryTxnService {

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationRepository notificationRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;
    private final WebPushSender webPushSender;
    private final FcmSender fcmSender;
    private final NotificationService outboundMailService;
    private final SmsMessagingClient smsMessagingClient;
    private final MetaWhatsAppMessagingClient whatsAppMessagingClient;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final ObjectMapper objectMapper;

    @Value("${app.notifications.delivery.max-attempts:8}")
    private int maxAttempts;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attemptDelivery(String deliveryId) {
        NotificationDelivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null || !NotificationDelivery.STATUS_PENDING.equals(delivery.getStatus())) {
            return;
        }

        Notification notification = notificationRepository.findById(delivery.getNotificationId()).orElse(null);
        if (notification == null) {
            markFailed(delivery, "notification_missing");
            return;
        }

        ParsedPayload payload = parsePayload(notification.getPayloadJson());

        boolean success = switch (delivery.getChannel()) {
            case NotificationDelivery.CHANNEL_IN_APP -> {
                markSent(delivery, "IN_APP");
                yield true;
            }
            case NotificationDelivery.CHANNEL_WEB_PUSH -> attemptWebPush(delivery, notification, payload);
            case NotificationDelivery.CHANNEL_EMAIL -> attemptEmail(delivery, notification, payload);
            case NotificationDelivery.CHANNEL_SMS -> attemptSms(delivery, notification, payload);
            case NotificationDelivery.CHANNEL_WHATSAPP -> attemptWhatsApp(delivery, notification, payload);
            default -> {
                markSkipped(delivery, "unknown_channel");
                yield true;
            }
        };

        if (!success) {
            scheduleRetryOrFail(delivery);
        }
    }

    private boolean attemptWebPush(
            NotificationDelivery delivery,
            Notification notification,
            ParsedPayload payload
    ) {
        if (notification.getUserId() == null || notification.getUserId().isBlank()) {
            markSkipped(delivery, "no_target_user");
            return true;
        }
        var tokens = deviceTokenRepository.findByBusinessIdAndUserIdAndRevokedAtIsNull(
                notification.getBusinessId(),
                notification.getUserId());
        if (tokens.isEmpty()) {
            markSkipped(delivery, "no_device_tokens");
            return true;
        }
        int webSent = webPushSender.sendToTokens(tokens, payload.title(), payload.body(), payload.actionUrl());
        int fcmSent = fcmSender.sendToTokens(tokens, payload.title(), payload.body());
        if (webSent > 0 || fcmSent > 0) {
            markSent(delivery, webSent > 0 ? "vapid" : "fcm");
            return true;
        }
        return false;
    }

    private boolean attemptEmail(
            NotificationDelivery delivery,
            Notification notification,
            ParsedPayload payload
    ) {
        if (notification.getUserId() == null || notification.getUserId().isBlank()) {
            markSkipped(delivery, "no_target_user");
            return true;
        }
        User user = userRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(notification.getUserId(), notification.getBusinessId())
                .orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            markSkipped(delivery, "no_email");
            return true;
        }
        String subject = payload.title() != null && !payload.title().isBlank() ? payload.title() : "Palmart";
        String text = buildSmsBody(payload);
        String html = buildNotificationHtml(payload);
        try {
            outboundMailService.sendNotificationEmail(user.getEmail().trim(), subject, text, html);
            markSent(delivery, "mail");
            return true;
        } catch (RuntimeException ex) {
            delivery.setLastError(truncate(ex.getMessage()));
            return false;
        }
    }

    private static String buildNotificationHtml(ParsedPayload payload) {
        String title = payload.title() != null && !payload.title().isBlank() ? payload.title() : "Palmart";
        String body = payload.body() != null ? payload.body() : "";
        String link = payload.actionUrl() != null && !payload.actionUrl().isBlank() ? payload.actionUrl() : "/";
        return """
                <!DOCTYPE html><html><body style="font-family:sans-serif;line-height:1.5">
                <h2>%s</h2><p>%s</p><p><a href="%s">Open in Palmart</a></p>
                </body></html>
                """.formatted(escapeHtml(title), escapeHtml(body), escapeHtml(link));
    }

    private static String escapeHtml(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.length() > 480 ? raw.substring(0, 480) : raw;
    }

    private boolean attemptSms(
            NotificationDelivery delivery,
            Notification notification,
            ParsedPayload payload
    ) {
        if (NotificationTypes.CREDIT_SALE_REMINDER.equals(notification.getType())) {
            markSkipped(delivery, "credit_reminder_dispatched_separately");
            return true;
        }
        return attemptExternalText(delivery, notification, payload, true);
    }

    private boolean attemptWhatsApp(
            NotificationDelivery delivery,
            Notification notification,
            ParsedPayload payload
    ) {
        if (NotificationTypes.CREDIT_SALE_REMINDER.equals(notification.getType())) {
            markSkipped(delivery, "credit_reminder_dispatched_separately");
            return true;
        }
        return attemptExternalText(delivery, notification, payload, false);
    }

    private boolean attemptExternalText(
            NotificationDelivery delivery,
            Notification notification,
            ParsedPayload payload,
            boolean sms
    ) {
        if (notification.getUserId() == null) {
            markSkipped(delivery, "no_target_user");
            return true;
        }
        TenantMessagingConfig messaging = messagingSettingsService.resolveForDispatch(notification.getBusinessId());
        if (!messaging.enabled()) {
            markSkipped(delivery, "messaging_disabled");
            return true;
        }
        User user = userRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(notification.getUserId(), notification.getBusinessId())
                .orElse(null);
        if (user == null || user.getPhone() == null || user.getPhone().isBlank()) {
            markSkipped(delivery, "no_phone");
            return true;
        }
        String body = buildSmsBody(payload);
        if (sms) {
            String digits = StkPhoneNormalizer.normalize(user.getPhone());
            if (digits == null) {
                markSkipped(delivery, "invalid_phone");
                return true;
            }
            SmsMessagingClient.SendResult result = smsMessagingClient.sendText(messaging, "+" + digits, body);
            if (result.sent()) {
                markSent(delivery, result.channel());
                return true;
            }
            if (result.stub()) {
                markSkipped(delivery, "sms_stub");
                return true;
            }
            delivery.setLastError(result.detail());
            return false;
        }
        String digits = StkPhoneNormalizer.normalize(user.getPhone());
        if (digits == null) {
            markSkipped(delivery, "invalid_phone");
            return true;
        }
        MetaWhatsAppMessagingClient.SendResult result = whatsAppMessagingClient.sendText(messaging, digits, body);
        if (result.sent()) {
            markSent(delivery, result.channel());
            return true;
        }
        if (result.skipped()) {
            markSkipped(delivery, result.detail());
            return true;
        }
        delivery.setLastError(result.detail());
        return false;
    }

    private void scheduleRetryOrFail(NotificationDelivery delivery) {
        int next = delivery.getAttemptCount() + 1;
        delivery.setAttemptCount(next);
        if (next >= maxAttempts) {
            delivery.setStatus(NotificationDelivery.STATUS_FAILED);
            delivery.setNextRetryAt(null);
        } else {
            delivery.setStatus(NotificationDelivery.STATUS_PENDING);
            delivery.setNextRetryAt(Instant.now().plusSeconds(backoffSeconds(next)));
        }
        deliveryRepository.save(delivery);
    }

    private void markSent(NotificationDelivery delivery, String provider) {
        delivery.setStatus(NotificationDelivery.STATUS_SENT);
        delivery.setProvider(provider);
        delivery.setSentAt(Instant.now());
        delivery.setLastError(null);
        delivery.setNextRetryAt(null);
        deliveryRepository.save(delivery);
    }

    private void markSkipped(NotificationDelivery delivery, String reason) {
        delivery.setStatus(NotificationDelivery.STATUS_SKIPPED);
        delivery.setLastError(reason);
        delivery.setNextRetryAt(null);
        deliveryRepository.save(delivery);
    }

    private void markFailed(NotificationDelivery delivery, String reason) {
        delivery.setStatus(NotificationDelivery.STATUS_FAILED);
        delivery.setLastError(reason);
        delivery.setNextRetryAt(null);
        deliveryRepository.save(delivery);
    }

    private ParsedPayload parsePayload(String json) {
        if (json == null || json.isBlank()) {
            return new ParsedPayload("Palmart", "", "/");
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            return new ParsedPayload(
                    stringVal(map.get("title")),
                    stringVal(map.get("body")),
                    stringVal(map.get("actionUrl")));
        } catch (Exception e) {
            return new ParsedPayload("Palmart", "", "/");
        }
    }

    private static String buildSmsBody(ParsedPayload payload) {
        if (payload.body() != null && !payload.body().isBlank()) {
            return payload.title() + ": " + payload.body();
        }
        return payload.title();
    }

    private static String stringVal(Object raw) {
        if (raw == null) {
            return "";
        }
        return String.valueOf(raw).trim();
    }

    private static long backoffSeconds(int attempt) {
        long base = Math.min(3600L, (long) Math.pow(2, attempt) * 15L);
        return base + ThreadLocalRandom.current().nextLong(0, 30);
    }

    private record ParsedPayload(String title, String body, String actionUrl) {
    }
}
