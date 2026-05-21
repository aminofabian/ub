package zelisline.ub.notifications.application;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.domain.NotificationTemplate;
import zelisline.ub.notifications.repository.NotificationTemplateRepository;

@Component
@RequiredArgsConstructor
public class NotificationTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    private final NotificationTemplateRepository templateRepository;

    public RenderedNotification render(String businessId, String type, Map<String, String> variables) {
        NotificationTemplate template = templateRepository
                .findFirstByBusinessIdAndTypeAndLocaleAndActiveTrueOrderByVersionDesc(businessId, type, "en")
                .or(() -> templateRepository.findFirstByBusinessIdIsNullAndTypeAndLocaleAndActiveTrueOrderByVersionDesc(
                        type, "en"))
                .orElse(null);

        if (template == null) {
            return new RenderedNotification(
                    humanizeType(type),
                    "",
                    "",
                    "operational",
                    "MEDIUM");
        }

        return new RenderedNotification(
                substitute(template.getTitleTemplate(), variables),
                substitute(template.getBodyTemplate(), variables),
                substituteNullable(template.getActionUrlTemplate(), variables),
                template.getCategory(),
                priorityForClass(template.getNotificationClass()));
    }

    private static String priorityForClass(String notificationClass) {
        if ("TRANSACTIONAL".equals(notificationClass)) {
            return "HIGH";
        }
        if ("PROMOTIONAL".equals(notificationClass)) {
            return "LOW";
        }
        return "MEDIUM";
    }

    private static String substitute(String template, Map<String, String> variables) {
        if (template == null) {
            return "";
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = variables.getOrDefault(key, "");
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString().trim();
    }

    private static String substituteNullable(String template, Map<String, String> variables) {
        if (template == null || template.isBlank()) {
            return "";
        }
        return substitute(template, variables);
    }

    private static String humanizeType(String type) {
        return type.replace('.', ' ').replace('_', ' ');
    }

    public record RenderedNotification(
            String title,
            String body,
            String actionUrl,
            String category,
            String priority) {
    }
}
