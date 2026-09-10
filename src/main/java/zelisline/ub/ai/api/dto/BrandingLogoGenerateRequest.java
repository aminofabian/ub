package zelisline.ub.ai.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Merchant request to generate a shop logo from a prompt.
 * {@code prompt} may be empty when {@code shopName} is present — the model
 * then invents a simple mark from the name and shop type.
 */
public record BrandingLogoGenerateRequest(
        @Size(max = 600) String prompt,
        @Size(max = 120) String shopName,
        @Size(max = 80) String shopType,
        @Size(max = 16) String primaryColor,
        @Size(max = 16) String accentColor
) {}
