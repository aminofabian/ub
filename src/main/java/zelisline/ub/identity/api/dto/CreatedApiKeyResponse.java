package zelisline.ub.identity.api.dto;

import java.time.Instant;
import java.util.List;

/** Full secret appears only in this create response (§3.5). */
public record CreatedApiKeyResponse(
        String id,
        String apiKey,
        String tokenPrefix,
        String label,
        List<String> scopes,
        Instant createdAt
) {
}
