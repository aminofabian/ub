package zelisline.ub.ai.application;

/**
 * Runtime-resolved SokoMind config (DB preferred over env).
 * Secrets are present for server-side provider calls only — never serialize to clients.
 */
public record ResolvedSokoMindConfig(
        boolean enabled,
        boolean guideEnabled,
        boolean brainEnabled,
        boolean eyeEnabled,
        String primaryProvider,
        String defaultLocale,
        String openaiApiKey,
        String openaiBaseUrl,
        String openaiMiniModel,
        String openaiSmartModel,
        String openaiVisionModel,
        String anthropicApiKey,
        String anthropicBaseUrl,
        String anthropicMiniModel,
        String anthropicSmartModel,
        String deepseekApiKey,
        String deepseekBaseUrl,
        String deepseekHost,
        String deepseekModel,
        String rapidapiDeepseekApiKey,
        String openrouterApiKey,
        String openrouterBaseUrl,
        String openrouterMiniModel,
        String openrouterSmartModel,
        String openrouterImageModel,
        boolean industryCompareEnabled,
        int industryCompareMinTwins,
        Integer dailyTokenBudgetPerTenant,
        int maxToolCallsPerRequest,
        String systemPromptExtra
) {
    public boolean primaryProviderConfigured() {
        return switch (primaryProvider == null ? "" : primaryProvider) {
            case "openai" -> openaiApiKey != null && !openaiApiKey.isBlank();
            case "anthropic" -> anthropicApiKey != null && !anthropicApiKey.isBlank();
            case "openrouter" -> openrouterApiKey != null && !openrouterApiKey.isBlank();
            case "deepseek" -> deepseekApiKey != null && !deepseekApiKey.isBlank();
            case "rapidapi_deepseek" ->
                    rapidapiDeepseekApiKey != null && !rapidapiDeepseekApiKey.isBlank();
            default -> false;
        };
    }

    public boolean openaiConfigured() {
        return openaiApiKey != null && !openaiApiKey.isBlank();
    }

    public boolean openrouterConfigured() {
        return openrouterApiKey != null && !openrouterApiKey.isBlank();
    }

    /** Logos: OpenRouter when it is the primary provider (or the only image key), else OpenAI. */
    public boolean imageGenerationAvailable() {
        return enabled() && (openaiConfigured() || openrouterConfigured());
    }

    /**
     * Which image backend to call. Prefer OpenRouter when it is the selected
     * chat provider, otherwise OpenAI, then OpenRouter as a fallback.
     */
    public String imageProvider() {
        String primary = primaryProvider == null ? "" : primaryProvider.toLowerCase();
        if ("openrouter".equals(primary) && openrouterConfigured()) {
            return "openrouter";
        }
        if (openaiConfigured()) {
            return "openai";
        }
        if (openrouterConfigured()) {
            return "openrouter";
        }
        return "";
    }
}
