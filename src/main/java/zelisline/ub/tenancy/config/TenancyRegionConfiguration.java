package zelisline.ub.tenancy.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SelfServeRegionProperties.class)
public class TenancyRegionConfiguration {
}
