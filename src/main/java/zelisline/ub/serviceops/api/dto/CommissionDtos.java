package zelisline.ub.serviceops.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request and response shapes for staff commission, tips and payouts (ADR 0014). */
public final class CommissionDtos {

    private CommissionDtos() {
    }

    public record CommissionPlanResponse(
            String staffProfileId,
            String displayName,
            boolean bookable,
            boolean current,
            /** {@code PERCENT}, {@code FIXED} or null for no commission on services. */
            String type,
            BigDecimal rate,
            BigDecimal productRate
    ) {
    }

    public record SetCommissionPlanRequest(
            @Size(max = 16) String type,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal rate,
            @DecimalMin("0") @Digits(integer = 3, fraction = 2) BigDecimal productRate
    ) {
    }

    public record StatementRow(
            String staffProfileId,
            String displayName,
            boolean current,
            long servicesDone,
            BigDecimal serviceRevenue,
            BigDecimal opening,
            BigDecimal commission,
            BigDecimal tips,
            /** Negative: earnings taken back by voids and refunds, net of reversed payouts. */
            BigDecimal reversals,
            BigDecimal adjustments,
            /** Positive: money paid to the staff member in the period. */
            BigDecimal paid,
            BigDecimal closing
    ) {
    }

    public record StatementResponse(
            LocalDate from,
            LocalDate to,
            List<StatementRow> rows,
            /** Everything owed to staff according to the staff ledger. */
            BigDecimal ledgerTotal,
            /** Commissions payable plus tips payable according to the general ledger. */
            BigDecimal generalLedgerTotal,
            boolean reconciled
    ) {
    }

    public record LedgerEntryResponse(
            String id,
            String staffProfileId,
            String branchId,
            String kind,
            String bucket,
            BigDecimal amount,
            BigDecimal baseAmount,
            String rule,
            String source,
            String saleId,
            String saleItemId,
            String reversesId,
            String payoutMethod,
            String reference,
            String reason,
            Instant occurredAt
    ) {
    }

    public record AdjustmentRequest(
            @NotNull @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record PayoutRequest(
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            /** {@code DRAWER}, {@code MPESA} or {@code OTHER}. */
            @NotBlank @Size(max = 16) String method,
            /** Required for {@code DRAWER}: the branch whose open till pays. */
            @Size(max = 36) String branchId,
            @Size(max = 191) String reference,
            /** Pay more than is owed, leaving a negative balance. */
            Boolean allowOverpay
    ) {
    }
}
