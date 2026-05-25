package zelisline.ub.grocery.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row in the grocery-counter "Top sellers" panel. Aggregated server-side
 * over a clerk's own invoices so the list survives page reloads.
 */
public record GroceryTopProductResponse(
        String id,
        String name,
        String sku,
        String thumbnailUrl,
        /** Distinct invoice count this item appears on (used as the headline rank). */
        long invoiceCount,
        /** Sum of quantities across the considered invoices. */
        BigDecimal totalQuantity,
        /** {@code created_at} of the most recent invoice this item appears on. */
        Instant lastInvoicedAt
) {
}
