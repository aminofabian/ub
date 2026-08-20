package zelisline.ub.platform.logs;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

/**
 * Registers {@link PlatformRequestLogInterceptor} for API and webhook traffic.
 *
 * <p>The request-log endpoints themselves are excluded so the live feed does
 * not log its own polling. WebSocket upgrade traffic is excluded too — those
 * connections are long-lived and not request-shaped.
 */
@Configuration
@RequiredArgsConstructor
public class PlatformRequestLogConfig implements WebMvcConfigurer {

    private final PlatformRequestLogRepository repository;
    private final RequestLogClassifier classifier;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PlatformRequestLogInterceptor(repository, classifier))
                .addPathPatterns("/api/v1/**", "/webhooks/**")
                .excludePathPatterns(
                        "/api/v1/realtime/**",
                        "/api/v1/super-admin/platform/request-logs/**");
    }
}
