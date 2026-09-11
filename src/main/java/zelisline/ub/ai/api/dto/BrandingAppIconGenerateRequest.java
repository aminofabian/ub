package zelisline.ub.ai.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Rebuild the shop's saved logo as a home-screen / PWA icon.
 * {@code prompt} is optional extra direction on top of matching the logo.
 */
public record BrandingAppIconGenerateRequest(
        @Size(max = 600) String prompt,
        @Size(max = 120) String shopName,
        @Size(max = 80) String shopType,
        @Size(max = 16) String primaryColor,
        @Size(max = 16) String accentColor
) {}
