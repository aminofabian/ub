package zelisline.ub.credits.api.dto;

import java.time.Instant;

public record CustomerPhoneResponse(
        String id,
        String phone,
        String maskedMsisdn,
        String assignedMsisdn,
        String maskedHint,
        boolean primary,
        Instant verifiedAt,
        Instant createdAt
) {
}
