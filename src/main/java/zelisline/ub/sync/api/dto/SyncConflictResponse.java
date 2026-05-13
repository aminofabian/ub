package zelisline.ub.sync.api.dto;

public record SyncConflictResponse(
        String id,
        String entityType,
        String entityId,
        String localVersion,
        String serverVersion,
        String resolution,
        String notes,
        String localSnapshot,
        String serverSnapshot,
        String createdBy,
        String createdAt,
        String resolvedAt,
        String resolvedBy
) {
}
