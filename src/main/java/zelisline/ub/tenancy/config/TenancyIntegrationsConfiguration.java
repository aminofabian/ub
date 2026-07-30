package zelisline.ub.tenancy.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import zelisline.ub.tenancy.integrations.hostafrica.HostAfricaProperties;
import zelisline.ub.tenancy.integrations.vercel.VercelProperties;

@Configuration
@EnableConfigurationProperties({VercelProperties.class, HostAfricaProperties.class})
public class TenancyIntegrationsConfiguration {
}
