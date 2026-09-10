package zelisline.ub.ai.api.dto;

/** One generated mark. {@code theme} is {@code light} or {@code dark}. */
public record BrandingLogoVariantDto(
        String theme,
        String mimeType,
        String imageBase64
) {}
