package zelisline.ub.notifications.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({WebPushProperties.class, FcmProperties.class})
public class NotificationsWebPushConfiguration {
}
