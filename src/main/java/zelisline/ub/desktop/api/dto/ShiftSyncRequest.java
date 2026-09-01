package zelisline.ub.desktop.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Batch of till shifts (with their sales) uploaded by a desktop install to the
 * cloud (DESKTOP_INSTALLATION.md §9c — the "up" direction of store-and-forward
 * sync).
 *
 * <p>The desktop pushes sales as they happen (realtime) and again at shift
 * close; the cloud ingests idempotently — a sale whose {@code idempotencyKey}
 * (or id) already exists is skipped, so a retried push after a partial failure
 * never double-counts.
 *
 * <p>Deliberately excludes cloud-owned fields: receipt numbers (unique per
 * business on the cloud), ledger journal references, and customer ids (customers
 * are not synced in v1 — the till keeps its own local references).
 */
public record ShiftSyncRequest(
        @Valid List<ShiftData> shifts,
        /** Customers created/edited on the till since the last upload. */
        @Valid List<CustomerData> customers,
        /** Suppliers created/edited on the till since the last upload. */
        @Valid List<SupplierData> suppliers
) {

    public record ShiftData(
        @NotBlank String id,
        @NotBlank String branchId,
        /**
         * Till device key ({@code X-Till-Device-Id}). Nullable: legacy shifts
         * opened before till-device tracking use the shared branch drawer
         * ({@code till_device_key IS NULL}) — same semantics as the schema and
         * {@code OpenShiftResolver}.
         */
        @Size(max = 64) String tillDeviceKey,
        @NotBlank String status,
        /** Cloud user id (the desktop remaps local cashier ids to the owner). */
        @NotBlank String openedBy,
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
        String customerId,
        Instant soldAt,
        Instant voidedAt,
        String voidNotes,
        BigDecimal refundedTotal,
        @Valid List<SaleItemData> items,
        @Valid List<SalePaymentData> payments
    ) {}

    /**
     * Customer (with phones + credit account) created or edited on the till —
     * upserted by the cloud before the sales that reference it, so the
     * {@code sales.customer_id} FK always resolves.
     */
    public record CustomerData(
        @NotBlank String id,
        @NotBlank String name,
        String email,
        String notes,
        @Valid List<CustomerPhoneData> phones,
        CreditAccountData creditAccount
    ) {}

    public record CustomerPhoneData(
        @NotBlank String id,
        @NotBlank String phone,
        boolean primary
    ) {}

    /**
     * Supplier (with contacts) created or edited on the till — upserted by the
     * cloud id-preservingly so both sides reference the same directory
     * (scopes/DESKTOP_SUPPLIERS_SYNC_SCOPE.md §4).
     */
    public record SupplierData(
        @NotBlank String id,
        @NotBlank String name,
        String code,
        String supplierType,
        String vatPin,
        boolean taxExempt,
        Integer creditTermsDays,
        BigDecimal creditLimit,
        String status,
        String notes,
        String paymentMethodPreferred,
        String paymentDetails,
        String payoutType,
        String payoutPhone,
        String payoutTillNumber,
        String payoutPaybillNumber,
        String payoutPaybillAccount,
        @Valid List<SupplierContactData> contacts
    ) {}

    public record SupplierContactData(
        @NotBlank String id,
        String name,
        String roleLabel,
        String phone,
        String email,
        boolean primary
    ) {}

    /** Live credit-account state; the till's balance is authoritative for its own edits. */
    public record CreditAccountData(
        @NotNull BigDecimal balanceOwed,
        BigDecimal walletBalance,
        int loyaltyPoints,
        BigDecimal creditLimit
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
