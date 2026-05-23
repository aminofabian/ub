package zelisline.ub.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.catalog.ai.deepseek")
public record CatalogAiProperties(
        String apiKey,
        String host,
        String url,
        String model
) {
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
