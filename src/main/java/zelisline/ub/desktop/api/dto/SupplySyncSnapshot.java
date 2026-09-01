package zelisline.ub.desktop.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Batch of Path B supply sessions exchanged between the desktop till and the
 * cloud (scopes/DESKTOP_SUPPLIERS_SYNC_SCOPE.md §4 — supplies sync).
 *
 * <p>Used in both directions: the till POSTs its dirty sessions to
 * {@code /api/v1/desktop/sync/supplies}, and the cloud serves cloud-entered
 * sessions from the same path via GET. Both sides ingest idempotently by
 * session id — a retried push/pull never duplicates a supply.
 *
 * <p>Deliberately document-level: sessions, their raw lines and the resulting
 * supplier invoice (+ lines) travel; inventory batches, stock movements and
 * ledger journals do not (each side's stock/ledger stays written only by its
 * own posting flow — same decision as the v1 sale ingest). Local batch
 * references ({@code inventory_batch_id}) are dropped on both sides because
 * the counterpart database has no such rows. Payments/disbursements stay
 * cloud-side in V1 (scope locked decision #4).
 */
public record SupplySyncSnapshot(
        @Valid List<SupplyData> supplies
) {

    public record SupplyData(
            @NotBlank String sessionId,
            @NotBlank String supplierId,
            @NotBlank String branchId,
            @NotNull Instant receivedAt,
            @NotBlank String status,
            String notes,
            Instant updatedAt,
            @Valid List<SupplyLineData> lines,
            /** Invoice the session produced (null while the session is still a draft). */
            @Valid InvoiceData invoice
    ) {}

    public record SupplyLineData(
            @NotBlank String id,
            int sortOrder,
            @NotBlank String descriptionText,
            @NotNull BigDecimal amountMoney,
            String suggestedItemId,
            @NotBlank String lineStatus,
            String postedItemId,
            BigDecimal usableQty,
            BigDecimal wastageQty,
            BigDecimal draftQty,
            BigDecimal draftUnitCost,
            BigDecimal draftSellPrice,
            LocalDate draftExpiryDate,
            String packOptionId
    ) {}

    public record InvoiceData(
            @NotBlank String id,
            @NotBlank String invoiceNumber,
            @NotNull LocalDate invoiceDate,
            LocalDate dueDate,
            @NotNull BigDecimal subtotal,
            @NotNull BigDecimal taxTotal,
            @NotNull BigDecimal grandTotal,
            @NotBlank String status,
            String notes,
            @Valid List<InvoiceLineData> lines
    ) {}

    public record InvoiceLineData(
            @NotBlank String id,
            @NotBlank String description,
            String itemId,
            @NotNull BigDecimal qty,
            @NotNull BigDecimal unitCost,
            @NotNull BigDecimal lineTotal,
            int sortOrder,
            String rawLineId
    ) {}
}
