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
            BigDecimal productRate,
            /** Whether this person has a login and can see their own earnings. */
            boolean canSignIn
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

    /** What each service brought in and what it cost in commission, for a period. */
    public record ServiceReportResponse(
            LocalDate from,
            LocalDate to,
            String branchId,
            List<ServiceReportRow> services,
            List<StaffReportRow> staff,
            BigDecimal revenue,
            BigDecimal commission,
            /** Products used, where a service lists them, at the latest delivery costs. */
            BigDecimal productCost,
            /** Revenue less commission and products used. Tips are not in it. */
            BigDecimal contribution
    ) {
    }

    public record ServiceReportRow(
            String serviceId,
            String name,
            long timesDone,
            BigDecimal revenue,
            BigDecimal commission,
            /** Products used: times done multiplied by today's cost of the products the service lists. */
            BigDecimal productCost,
            BigDecimal contribution,
            /** Planned minutes of work, from the duration set on the service when it was sold. */
            long minutes
    ) {
    }

    public record StaffReportRow(
            String staffProfileId,
            String displayName,
            long servicesDone,
            BigDecimal revenue,
            BigDecimal commission,
            BigDecimal tips,
            long minutes,
            /** Free redos this person did in the period. */
            long redos
    ) {
    }

    /**
     * What each chair, bed or room brought in over a period: the services done on it and their
     * value. {@code unplaced} is work that was paid for with no place recorded against it.
     */
    public record ChairReportResponse(
            LocalDate from,
            LocalDate to,
            String branchId,
            List<ChairReportRow> chairs,
            BigDecimal revenue,
            BigDecimal unplacedRevenue,
            long unplacedServices
    ) {
    }

    public record ChairReportRow(
            String resourceId,
            String name,
            String resourceType,
            /** False for a place taken out of use that still earned something in the period. */
            boolean active,
            long servicesDone,
            /** Visits that had at least one service on this place. */
            long customers,
            BigDecimal revenue,
            /** Planned minutes of the services done on it. */
            long minutes
    ) {
    }

    public record EarningsSmsResponse(boolean sent, String phoneMasked, String message) {
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
