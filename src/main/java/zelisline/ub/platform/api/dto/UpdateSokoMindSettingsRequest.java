package zelisline.ub.platform.api.dto;

/**
 * Secret fields: {@code null} = leave unchanged; blank string = clear stored value.
 * Non-secret fields: {@code null} = leave unchanged; blank = clear (fall back to env if any).
 * Booleans / ints: {@code null} = leave unchanged.
 */
public record UpdateSokoMindSettingsRequest(
        Boolean sokomindEnabled,
        Boolean guideEnabled,
        Boolean brainEnabled,
        Boolean eyeEnabled,
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
        Boolean industryCompareEnabled,
        Integer industryCompareMinTwins,
        Integer dailyTokenBudgetPerTenant,
        Boolean clearDailyTokenBudget,
        Integer maxToolCallsPerRequest,
        String systemPromptExtra
) {}
