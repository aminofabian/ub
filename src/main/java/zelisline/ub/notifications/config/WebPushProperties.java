package zelisline.ub.notifications.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notifications.web-push")
public record WebPushProperties(
        boolean enabled,
        String vapidPublicKey,
        String vapidPrivateKey,
        String subject
) {
    public boolean configured() {
        return enabled
                && vapidPublicKey != null && !vapidPublicKey.isBlank()
                && vapidPrivateKey != null && !vapidPrivateKey.isBlank()
                && subject != null && !subject.isBlank();
    }
}
