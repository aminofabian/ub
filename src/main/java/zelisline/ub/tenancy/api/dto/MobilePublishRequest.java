package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.Pattern;

/**
 * Optional body for {@code POST /api/v1/businesses/me/mobile/publish}.
 */
public record MobilePublishRequest(
        @Pattern(regexp = "shopper|cashier|grocery|admin|stock") String app,
        @Pattern(regexp = "all|android|ios") String platform
) {
    public String resolvedApp() {
        return app == null || app.isBlank() ? "shopper" : app.trim();
    }

    public String resolvedPlatform() {
        return platform == null || platform.isBlank() ? "all" : platform.trim();
    }
}
