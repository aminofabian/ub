package zelisline.ub.payments.api.dto;

import java.time.Instant;

public record PlatformDarajaSettingsResponse(
        boolean enabled,
        String environment,
        String shortcodeType,
        String shortcode,
        boolean hasCredentials,
        String consumerKeyHint,
        Instant updatedAt
) {
}
