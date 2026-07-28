package zelisline.ub.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Env fallbacks for SokoMind. Super Admin DB values win when set
 * (see {@link zelisline.ub.platform.application.PlatformSokoMindSettingsService}).
 */
@ConfigurationProperties(prefix = "app.sokomind")
public record SokoMindProperties(
        boolean enabled,
        boolean guideEnabled,
        boolean brainEnabled,
        boolean eyeEnabled,
        String primaryProvider,
        String defaultLocale,
        OpenAi openai,
        Anthropic anthropic,
        DeepSeek deepseek,
        boolean industryCompareEnabled,
        int industryCompareMinTwins,
        Integer dailyTokenBudgetPerTenant,
        int maxToolCallsPerRequest
) {
    public SokoMindProperties {
        if (primaryProvider == null || primaryProvider.isBlank()) {
            primaryProvider = "openai";
        }
        if (defaultLocale == null || defaultLocale.isBlank()) {
            defaultLocale = "en-KE";
        }
        if (openai == null) {
            openai = new OpenAi("", "", "gpt-4o-mini", "gpt-4.1", "gpt-4o");
        }
        if (anthropic == null) {
            anthropic = new Anthropic("", "", "claude-haiku-4-5-20251001", "claude-sonnet-4-5-20250929");
        }
        if (deepseek == null) {
            deepseek = new DeepSeek(
                    "",
                    "https://deepseek-v31.p.rapidapi.com/",
                    "deepseek-v31.p.rapidapi.com",
                    "DeepSeek-V3-0324");
        }
        if (industryCompareMinTwins <= 0) {
            industryCompareMinTwins = 8;
        }
        if (maxToolCallsPerRequest <= 0) {
            maxToolCallsPerRequest = 8;
        }
    }

    public record OpenAi(
            String apiKey,
            String baseUrl,
            String miniModel,
            String smartModel,
            String visionModel
    ) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Anthropic(
            String apiKey,
            String baseUrl,
            String miniModel,
            String smartModel
    ) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record DeepSeek(String apiKey, String baseUrl, String host, String model) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
