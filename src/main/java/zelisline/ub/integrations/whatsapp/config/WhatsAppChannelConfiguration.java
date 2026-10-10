package zelisline.ub.integrations.whatsapp.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WhatsAppChannelProperties.class)
public class WhatsAppChannelConfiguration {
}
