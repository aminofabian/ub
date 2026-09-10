package zelisline.ub.ai.api.dto;

/** One generated asset. {@code theme} is {@code light}, {@code dark}, {@code favicon}, or {@code og}. */
public record BrandingLogoVariantDto(
        String theme,
        String mimeType,
        String imageBase64
) {}
