package zelisline.ub.platform.logs;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DesktopLogIngestProperties.class)
public class DesktopLogConfiguration {
}
