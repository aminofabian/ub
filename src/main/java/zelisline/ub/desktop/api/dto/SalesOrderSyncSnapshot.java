package zelisline.ub.desktop.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Batch of supplier trade orders mirrored cloud -> till (pull only in slice S1).
 *
 * <p>Trade orders are placed by buyers in the online shop. The till mirrors them
 * by id so a supplier sees "to answer" and "to dispatch" at the counter. What the
 * supplier DOES to an order (answer, dispatch, cancel) does not travel as a
 * changed row; it travels as an action in a later slice (S2), applied once by the
 * cloud's own {@code SalesOrderService}. See {@code DESKTOP_SUPPLIER_TRADE_SYNC_SCOPE.md}.
 *
 * <p>Idempotent on the till side: it upserts by order id and replaces lines
 * wholesale.
 */
public record SalesOrderSyncSnapshot(
        @Valid List<OrderData> orders
) {

    public record OrderData(
            @NotBlank String id,
            @NotBlank String businessId,
            @NotBlank String branchId,
            @NotBlank String status,
            @NotBlank String source,
            @NotBlank String currency,
            @NotNull BigDecimal total,
            String buyerName,
            String buyerPhone,
            String buyerEmail,
            String buyerBusinessName,
            String buyerBusinessId,
            String buyerPhoneKey,
            String paymentTerms,
            String tradeAccountId,
            boolean overCreditLimit,
            String notes,
            Instant answeredAt,
            Instant dispatchedAt,
            Instant createdAt,
            Instant updatedAt,
            @Valid List<LineData> lines
    ) {}

    public record LineData(
            @NotBlank String id,
            @NotBlank String itemId,
            @NotBlank String itemName,
            @NotNull BigDecimal quantity,
            @NotNull BigDecimal unitPrice,
            @NotNull BigDecimal lineTotal,
            int lineIndex,
            String status,
            BigDecimal acceptedQty
    ) {}
}
