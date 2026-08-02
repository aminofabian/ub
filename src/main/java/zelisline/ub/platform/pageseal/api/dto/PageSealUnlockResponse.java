package zelisline.ub.platform.pageseal.api.dto;

import java.time.Instant;

public record PageSealUnlockResponse(String unlockToken, Instant expiresAt) {
}
