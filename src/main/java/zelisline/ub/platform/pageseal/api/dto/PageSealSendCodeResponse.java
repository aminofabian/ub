package zelisline.ub.platform.pageseal.api.dto;

import java.time.Instant;

public record PageSealSendCodeResponse(
        String phoneHint,
        Instant expiresAt,
        String channel,
        String devCode
) {
}
