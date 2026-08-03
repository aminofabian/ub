package zelisline.ub.posdraft.api.dto;

import java.time.Instant;

public record PosDraftAuditEntryResponse(
        String id,
        String draftId,
        String userId,
        String userName,
        String action,
        String lineId,
        String oldValue,
        String newValue,
        Instant createdAt
) {
}
