package zelisline.ub.ai.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Merchant request to generate a shop logo pair (light + dark).
 * {@code prompt} may be empty — the model then uses the platform default brief.
 */
public record BrandingLogoGenerateRequest(
        @Size(max = 600) String prompt,
        @Size(max = 120) String shopName,
        @Size(max = 80) String shopType,
        @Size(max = 16) String primaryColor,
        @Size(max = 16) String accentColor
) {}
