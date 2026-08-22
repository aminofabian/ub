package zelisline.ub.platform.api.dto;

import java.time.Instant;

/** API keys / secrets are never returned; use {@code has*} flags. */
public record SokoMindSettingsResponse(
        boolean sokomindEnabled,
        boolean guideEnabled,
        boolean brainEnabled,
        boolean eyeEnabled,
        String primaryProvider,
        String defaultLocale,
        boolean hasOpenaiApiKey,
        String openaiBaseUrl,
        String openaiMiniModel,
        String openaiSmartModel,
        String openaiVisionModel,
        boolean hasAnthropicApiKey,
        String anthropicBaseUrl,
        String anthropicMiniModel,
        String anthropicSmartModel,
        boolean hasDeepseekApiKey,
        boolean hasRapidapiDeepseekApiKey,
        String deepseekBaseUrl,
        String deepseekHost,
        String deepseekModel,
        boolean industryCompareEnabled,
        int industryCompareMinTwins,
        Integer dailyTokenBudgetPerTenant,
        int maxToolCallsPerRequest,
        String systemPromptExtra,
        boolean envOpenaiConfigured,
        boolean envAnthropicConfigured,
        boolean envDeepseekConfigured,
        boolean secretsReadable,
        String secretsError,
        boolean encryptionEphemeral,
        Instant updatedAt
) {}
