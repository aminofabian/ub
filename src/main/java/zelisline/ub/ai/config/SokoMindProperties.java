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
        OpenRouter openrouter,
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
            openai = new OpenAi("", "", "gpt-4o-mini", "gpt-4.1", "gpt-4o", "gpt-image-1");
        }
        if (anthropic == null) {
            anthropic = new Anthropic("", "", "claude-haiku-4-5-20251001", "claude-sonnet-4-5-20250929");
        }
        if (openrouter == null) {
            openrouter = new OpenRouter(
                    "",
                    "https://openrouter.ai/api/v1",
                    "z-ai/glm-5.3-flash",
                    "z-ai/glm-4.6",
                    "google/gemini-2.5-flash-image");
        }
        if (deepseek == null) {
            deepseek = new DeepSeek(
                    "",
                    "https://api.deepseek.com/chat/completions",
                    "deepseek-v31.p.rapidapi.com",
                    "DeepSeek-V3-0324",
                    "");
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
            String visionModel,
            String imageModel
    ) {
        public OpenAi {
            if (imageModel == null || imageModel.isBlank()) {
                imageModel = "gpt-image-1";
            }
        }

        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record OpenRouter(
            String apiKey,
            String baseUrl,
            String miniModel,
            String smartModel,
            String imageModel
    ) {
        public OpenRouter {
            if (baseUrl == null || baseUrl.isBlank()) {
                baseUrl = "https://openrouter.ai/api/v1";
            }
            if (miniModel == null || miniModel.isBlank()) {
                miniModel = "z-ai/glm-5.3-flash";
            }
            if (smartModel == null || smartModel.isBlank()) {
                smartModel = "z-ai/glm-4.6";
            }
            if (imageModel == null || imageModel.isBlank()) {
                imageModel = "google/gemini-2.5-flash-image";
            }
        }

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

    public record DeepSeek(
            String apiKey,
            String baseUrl,
            String host,
            String model,
            String rapidapiApiKey
    ) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }

        public boolean rapidapiConfigured() {
            return rapidapiApiKey != null && !rapidapiApiKey.isBlank();
        }
    }
}
