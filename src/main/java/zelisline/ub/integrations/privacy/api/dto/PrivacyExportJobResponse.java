package zelisline.ub.integrations.privacy.api.dto;

import java.time.Instant;

public record PrivacyExportJobResponse(
        String id,
        String status,
        String subjectType,
        String subjectId,
        String downloadUrl,
        Instant expiresAt,
        String errorMessage
) {}
