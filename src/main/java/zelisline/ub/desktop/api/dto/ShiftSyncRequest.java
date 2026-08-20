package zelisline.ub.desktop.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * Batch of closed shifts (with their sales) uploaded by a desktop till to the
 * cloud (DESKTOP_INSTALLATION.md §9c — the "up" direction of store-and-forward
 * sync).
 *
 * <p>The desktop sends each shift exactly once per {@code cloud_synced_at}
 * marker, and the cloud ingests idempotently: a sale whose
 * {@code idempotencyKey} (or id) already exists is skipped, so a retried push
 * after a partial failure never double-counts.
 *
 * <p>Deliberately excludes cloud-owned fields: receipt numbers (unique per
 * business on the cloud), ledger journal references, and customer ids (customers
 * are not synced in v1 — the till keeps its own local references).
 */
public record ShiftSyncRequest(@Valid @NotEmpty List<ShiftData> shifts) {

    public record ShiftData(
        @NotBlank String id,
        @NotBlank String branchId,
        @NotBlank String tillDeviceKey,
        @NotBlank String status,
        @NotNull BigDecimal openingCash,
        @NotNull BigDecimal expectedClosingCash,
        BigDecimal countedClosingCash,
        BigDecimal closingVariance,
        String openingNotes,
        String closingNotes,
        String varianceReason,
        boolean blindClosing,
        @NotNull Instant openedAt,
        Instant closedAt,
        @Valid List<SaleData> sales
    ) {}

    public record SaleData(
        @NotBlank String id,
        @NotBlank String branchId,
        @NotBlank String status,
        @NotBlank String idempotencyKey,
        @NotNull BigDecimal grandTotal,
        BigDecimal cashReceived,
        /** Cloud user id (the desktop remaps local cashier ids to the owner). */
        @NotBlank String soldBy,
        Instant soldAt,
        Instant voidedAt,
        String voidNotes,
        BigDecimal refundedTotal,
        @Valid List<SaleItemData> items,
        @Valid List<SalePaymentData> payments
    ) {}

    public record SaleItemData(
        @NotBlank String id,
        int lineIndex,
        String lineKind,
        String lineLabel,
        String itemId,
        String batchId,
        @NotNull BigDecimal quantity,
        @NotNull BigDecimal unitPrice,
        @NotNull BigDecimal lineTotal,
        BigDecimal unitCost,
        BigDecimal costTotal,
        BigDecimal profit,
        BigDecimal regularUnitPrice,
        BigDecimal discountAmount,
        String discountId,
        String discountName
    ) {}

    public record SalePaymentData(
        @NotBlank String id,
        @NotBlank String method,
        @NotNull BigDecimal amount,
        String reference,
        int sortOrder
    ) {}
}
