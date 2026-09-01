package zelisline.ub.desktop.api.dto;

/**
 * Acknowledgment for an ingested supply batch — lets the till know how many
 * Path B sessions were new vs already seen, so it can stamp
 * {@code raw_purchase_sessions.cloud_synced_at} only for accepted work.
 */
public record SupplySyncAck(
        int sessionsIngested,
        int sessionsSkipped
) {}
