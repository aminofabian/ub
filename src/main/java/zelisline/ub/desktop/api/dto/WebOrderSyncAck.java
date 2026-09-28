package zelisline.ub.desktop.api.dto;

import java.util.List;

/**
 * Acknowledgment for an ingested web-order batch: how many orders the cloud
 * accepted as new mirrors, how many till-side fulfillment confirmations were
 * applied (and so notified the customer), how many were skipped, and — since
 * the till may only stamp a confirmation the cloud has actually handled — the
 * ids the till may mark synced. A skipped-but-transient order is deliberately
 * absent from {@code processedOrderIds} so the till retries it instead of
 * dropping the confirmation.
 */
public record WebOrderSyncAck(
        int ordersIngested,
        int confirmationsApplied,
        int confirmationsSkipped,
        List<String> processedOrderIds
) {}
