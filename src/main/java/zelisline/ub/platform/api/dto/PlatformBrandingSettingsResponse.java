package zelisline.ub.platform.api.dto;

import java.time.Instant;

/**
 * Super-admin view of platform branding. {@code emailLogoUrl} null = the
 * built-in "K" wordmark badge is used in platform email.
 */
public record PlatformBrandingSettingsResponse(
        String emailLogoUrl,
        Instant updatedAt
) {}
