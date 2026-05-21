package zelisline.ub.notifications.application;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.NotificationCategories;
import zelisline.ub.notifications.domain.NotificationTemplate;
import zelisline.ub.notifications.repository.NotificationTemplateRepository;

@Component
@RequiredArgsConstructor
public class NotificationPolicyEngine {

    private static final String CLASS_TRANSACTIONAL = "TRANSACTIONAL";
    private static final String CLASS_PROMOTIONAL = "PROMOTIONAL";

    private final NotificationPreferenceService preferenceService;
    private final NotificationTemplateRepository templateRepository;

    public boolean mayDeliverToUser(
            String businessId,
            String userId,
            String notificationType,
            String category,
            String priority,
            String channel
    ) {
        if (!preferenceService.isChannelEnabled(businessId, userId, category, channel)) {
            return false;
        }
        String notificationClass = resolveClass(businessId, notificationType);
        boolean high = "HIGH".equalsIgnoreCase(priority);
        if (preferenceService.isInQuietHours(businessId, userId, high)) {
            return false;
        }
        if (CLASS_PROMOTIONAL.equals(notificationClass)
                || NotificationCategories.PROMO.equals(category)
                || NotificationCategories.ENGAGEMENT.equals(category)) {
            if (!preferenceService.isPromotionalDeliveryAllowed(businessId, userId)) {
                return false;
            }
        }
        return true;
    }

    public String resolveClass(String businessId, String type) {
        NotificationTemplate template = templateRepository
                .findFirstByBusinessIdAndTypeAndLocaleAndActiveTrueOrderByVersionDesc(businessId, type, "en")
                .or(() -> templateRepository.findFirstByBusinessIdIsNullAndTypeAndLocaleAndActiveTrueOrderByVersionDesc(
                        type, "en"))
                .orElse(null);
        if (template == null) {
            return CLASS_TRANSACTIONAL;
        }
        return template.getNotificationClass();
    }

    public String resolveCategory(String businessId, String type) {
        NotificationTemplate template = templateRepository
                .findFirstByBusinessIdAndTypeAndLocaleAndActiveTrueOrderByVersionDesc(businessId, type, "en")
                .or(() -> templateRepository.findFirstByBusinessIdIsNullAndTypeAndLocaleAndActiveTrueOrderByVersionDesc(
                        type, "en"))
                .orElse(null);
        if (template == null) {
            return NotificationCategories.ORDERS;
        }
        return template.getCategory();
    }
}
