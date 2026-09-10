package zelisline.ub.ai.api.dto;

/** PNG (or JPEG) bytes as Base64 so onboarding can preview before upload. */
public record BrandingLogoGenerateResponse(
        String requestId,
        String mimeType,
        String imageBase64
) {}
