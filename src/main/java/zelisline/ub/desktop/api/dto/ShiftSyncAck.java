package zelisline.ub.desktop.api.dto;

/**
 * Acknowledgment for an ingested shift batch — lets the till know how many
 * shifts/sales were new vs already seen, and when to mark them synced.
 */
public record ShiftSyncAck(
        int shiftsIngested,
        int salesIngested,
        int salesSkipped
) {}
