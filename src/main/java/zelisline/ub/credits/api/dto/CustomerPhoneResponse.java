package zelisline.ub.credits.api.dto;

import java.time.Instant;

public record CustomerPhoneResponse(
        String id,
        String phone,
        boolean primary,
        Instant createdAt
) {
}
