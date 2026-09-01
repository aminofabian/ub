package zelisline.ub.desktop.api.dto;

/**
 * Acknowledgment for an ingested web-order batch: how many orders the cloud
 * accepted as new mirrors, how many till-side fulfillment confirmations were
 * applied (and so notified the customer), and how many were skipped because
 * the cloud already knew that state (retried push) or couldn't apply it
 * (unknown order / illegal transition).
 */
public record WebOrderSyncAck(
        int ordersIngested,
        int confirmationsApplied,
        int confirmationsSkipped
) {}
