package zelisline.ub.purchasing.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.Size;

/**
 * Partial update for an open Path B draft session (notes / receive time / client draft blob).
 */
public record PatchPathBSessionRequest(
        Instant receivedAt,
        @Size(max = 5000) String notes,
        /** Opaque JSON for client-only draft extras (extras costs, UI flags). */
        @Size(max = 65000) String clientDraftJson
) {
}
