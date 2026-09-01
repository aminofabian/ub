package zelisline.ub.desktop.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Batch of storefront web orders exchanged between the desktop till and the
 * cloud (orders + order confirmations sync).
 *
 * <p>Used in both directions:
 *
 * <ul>
 *   <li><b>Pull (cloud → till):</b> orders placed in the online shop (status +
 *       fulfillment status) are mirrored to the till, so the cashier sees "new
 *       paid order awaiting confirmation" without opening the web dashboard.
 *       Upsert semantics — orders change over time, so the till replaces its
 *       mirror by id.</li>
 *   <li><b>Push (till → cloud):</b> an order the cashier confirmed at the till
 *       (fulfillment status advance) flows back up. The cloud replays the
 *       transition through its own {@code WebOrderFulfillmentService.advance},
 *       so the customer's confirmation notification fires from the same code
 *       path a web-side confirmation uses — one writer, no parallel pipeline.
 *       Only fulfillment state travels; payment state stays cloud-owned.</li>
 * </ul>
 *
 * <p>Idempotent on both sides by order id; a retried push/pull never
 * double-confirms (the cloud's advance() is a no-op for an already-applied
 * target status).
 */
public record WebOrderSyncSnapshot(
        @Valid List<OrderData> orders
) {

    public record OrderData(
            @NotBlank String id,
            String code,
            @NotBlank String channel,
            @NotBlank String catalogBranchId,
            @NotBlank String status,
            /** The fulfillment state (awaiting_confirmation/confirmed/dispatched/completed) — the synced field. */
            String fulfillmentStatus,
            @NotBlank String currency,
            @NotNull BigDecimal grandTotal,
            @NotBlank String customerName,
            @NotBlank String customerPhone,
            String customerEmail,
            String notes,
            Instant paidAt,
            Instant createdAt,
            Instant updatedAt,
            Instant pickupTicketPrintedAt,
            Instant expiresAt,
            @Valid List<LineData> lines
    ) {}

    public record LineData(
            @NotBlank String id,
            @NotBlank String itemId,
            @NotBlank String itemName,
            String variantName,
            @NotNull BigDecimal quantity,
            @NotNull BigDecimal unitPrice,
            @NotNull BigDecimal lineTotal,
            int lineIndex
    ) {}
}
