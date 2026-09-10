package zelisline.ub.platform.api.dto;

import java.time.Instant;

public record PlatformAuthSettingsResponse(
        boolean emailVerificationRequired,
        Instant updatedAt
) {}
