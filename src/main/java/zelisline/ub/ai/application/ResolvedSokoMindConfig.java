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
            case "deepseek", "rapidapi_deepseek" ->
                    deepseekApiKey != null && !deepseekApiKey.isBlank();
            default -> false;
        };
    }
}
