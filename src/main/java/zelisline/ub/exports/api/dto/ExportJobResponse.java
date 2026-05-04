package zelisline.ub.exports.api.dto;

import java.time.Instant;

/** Phase 7 Slice 6 — export job metadata returned by the API. */
public record ExportJobResponse(
        String id,
        String status,
        String reportKey,
        String format,
        String downloadUrl,
        Instant expiresAt,
        String errorMessage
) {
}
