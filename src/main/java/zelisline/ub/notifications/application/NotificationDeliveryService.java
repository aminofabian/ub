package zelisline.ub.notifications.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.NotificationTypes;
import zelisline.ub.notifications.domain.Notification;
import zelisline.ub.notifications.domain.NotificationDelivery;
import zelisline.ub.notifications.domain.NotificationTemplate;
import zelisline.ub.notifications.repository.NotificationDeliveryRepository;
import zelisline.ub.notifications.repository.NotificationTemplateRepository;

@Service
@RequiredArgsConstructor
public class NotificationDeliveryService {

    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationTemplateRepository templateRepository;
    private final StaffWebPushFanoutService staffWebPushFanoutService;
    private final StaffEmailFanoutService staffEmailFanoutService;
    private final NotificationPreferenceService preferenceService;
    private final NotificationPolicyEngine policyEngine;
    private final ObjectMapper objectMapper;

    @Transactional
    public void recordDeliveriesForNotification(Notification notification) {
        List<String> channels = filterChannelsForRecipient(
                notification,
                resolveChannels(notification.getBusinessId(), notification.getType()));
        Instant now = Instant.now();
        for (String channel : channels) {
            if (!shouldEnqueueChannel(notification, channel)) {
                continue;
            }
            NotificationDelivery d = new NotificationDelivery();
            d.setNotificationId(notification.getId());
            d.setBusinessId(notification.getBusinessId());
            d.setChannel(channel);
            if (NotificationDelivery.CHANNEL_IN_APP.equals(channel)) {
                d.setStatus(NotificationDelivery.STATUS_SENT);
                d.setProvider("IN_APP");
                d.setSentAt(now);
            } else {
                d.setStatus(NotificationDelivery.STATUS_PENDING);
                d.setNextRetryAt(now);
            }
            deliveryRepository.save(d);
        }

        if (notification.getUserId() == null && isStaffWebPushType(notification.getType())) {
            staffWebPushFanoutService.fanoutForStaffAlert(notification);
        }
        if (notification.getUserId() == null && StaffEmailFanoutService.isStaffDigestType(notification.getType())) {
            staffEmailFanoutService.fanoutForStaffDigest(notification);
        }
    }

    private boolean shouldEnqueueChannel(Notification notification, String channel) {
        if (NotificationDelivery.CHANNEL_IN_APP.equals(channel)) {
            return true;
        }
        if (NotificationTypes.CREDIT_SALE_REMINDER.equals(notification.getType())
                && (NotificationDelivery.CHANNEL_SMS.equals(channel)
                || NotificationDelivery.CHANNEL_WHATSAPP.equals(channel))) {
            return false;
        }
        if (NotificationDelivery.CHANNEL_WEB_PUSH.equals(channel)) {
            return notification.getUserId() != null && !notification.getUserId().isBlank();
        }
        if (NotificationDelivery.CHANNEL_EMAIL.equals(channel)) {
            return notification.getUserId() != null && !notification.getUserId().isBlank();
        }
        if (NotificationDelivery.CHANNEL_SMS.equals(channel)
                || NotificationDelivery.CHANNEL_WHATSAPP.equals(channel)) {
            return notification.getUserId() != null && !notification.getUserId().isBlank();
        }
        return false;
    }

    private static boolean isStaffWebPushType(String type) {
        return NotificationTypes.STOREFRONT_ORDER_PLACED.equals(type)
                || NotificationTypes.STOREFRONT_ORDER_PAID.equals(type);
    }

    private List<String> filterChannelsForRecipient(Notification notification, List<String> channels) {
        if (notification.getUserId() == null || notification.getUserId().isBlank()) {
            return channels;
        }
        String category = notification.getCategory() != null && !notification.getCategory().isBlank()
                ? notification.getCategory()
                : policyEngine.resolveCategory(notification.getBusinessId(), notification.getType());
        return channels.stream()
                .filter(channel -> preferenceService.isChannelEnabled(
                        notification.getBusinessId(),
                        notification.getUserId(),
                        category,
                        channel))
                .toList();
    }

    private List<String> resolveChannels(String businessId, String type) {
        NotificationTemplate template = templateRepository
                .findFirstByBusinessIdAndTypeAndLocaleAndActiveTrueOrderByVersionDesc(businessId, type, "en")
                .or(() -> templateRepository.findFirstByBusinessIdIsNullAndTypeAndLocaleAndActiveTrueOrderByVersionDesc(
                        type, "en"))
                .orElse(null);
        if (template == null || template.getDefaultChannelsJson() == null) {
            return List.of(NotificationDelivery.CHANNEL_IN_APP);
        }
        try {
            List<String> parsed = objectMapper.readValue(
                    template.getDefaultChannelsJson(),
                    new TypeReference<>() {});
            if (parsed == null || parsed.isEmpty()) {
                return List.of(NotificationDelivery.CHANNEL_IN_APP);
            }
            return parsed;
        } catch (Exception e) {
            return List.of(NotificationDelivery.CHANNEL_IN_APP);
        }
    }
}
