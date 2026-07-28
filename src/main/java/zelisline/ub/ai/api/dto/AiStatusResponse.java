package zelisline.ub.ai.api.dto;

public record AiStatusResponse(
        boolean enabled,
        boolean guideEnabled,
        boolean brainEnabled,
        boolean eyeEnabled,
        boolean providerConfigured,
        String primaryProvider,
        String defaultLocale
) {}
