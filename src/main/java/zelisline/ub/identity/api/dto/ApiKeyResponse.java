package zelisline.ub.identity.api.dto;

import java.time.Instant;
import java.util.List;

public record ApiKeyResponse(
        String id,
        String label,
        String tokenPrefix,
        List<String> scopes,
        boolean active,
        Instant lastUsedAt,
        Instant expiresAt,
        Instant createdAt
) {
}
