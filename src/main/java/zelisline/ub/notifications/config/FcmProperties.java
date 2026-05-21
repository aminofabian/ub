package zelisline.ub.notifications.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.notifications.fcm")
public record FcmProperties(
        boolean enabled,
        String serverKey
) {
    public boolean configured() {
        return enabled && serverKey != null && !serverKey.isBlank();
    }
}
