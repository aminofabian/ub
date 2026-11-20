package zelisline.ub.pharmacy.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Request and response shapes for ordering, central purchasing and scheme claims (ADR 0033, Phase 3). */
public final class PharmacyNetworkDtos {

    private PharmacyNetworkDtos() {
    }

    // ── Ordering forecast ─────────────────────────────────────────────

    public record OrderingSettings(
            @NotNull @Min(1) @Max(120) Integer leadDays,
            @NotNull @Min(1) @Max(365) Integer coverDays
    ) {
    }

    /** One product at one branch. Quantities are in the unit stock is held in. */
    public record BranchNeed(
            String branchId,
            BigDecimal soldInWindow,
            BigDecimal dailyRate,
            BigDecimal sellable,
            BigDecimal onOrder,
            /** Days the sellable stock lasts at the current rate. Null when nothing sold. */
            BigDecimal daysOfCover,
            /** Stock that, at the current rate, will still be on the shelf when it expires. */
            BigDecimal expiringFirst,
            BigDecimal suggested
    ) {
    }

    public record ForecastRow(
            String itemId,
            String name,
            boolean medicine,
            /** The latest cost seen on stock on sale; null when there is none. */
            BigDecimal unitCost,
            BigDecimal suggestedTotal,
            BigDecimal expiringFirstTotal,
            BigDecimal lowestDaysOfCover,
            List<BranchNeed> branches
    ) {
    }

    public record Forecast(int windowDays, int leadDays, int coverDays, boolean truncated, List<ForecastRow> rows) {
    }

    // ── Central purchasing ────────────────────────────────────────────

    public record CentralOrderRequest(
            @NotBlank @Size(max = 36) String supplierId,
            @NotBlank @Size(max = 36) String receivingBranchId,
            @NotEmpty @Size(max = 300) List<@Valid @NotNull Line> lines
    ) {
        public record Line(
                @NotBlank @Size(max = 36) String itemId,
                @NotNull @Positive BigDecimal unitCost,
                @NotEmpty @Size(max = 50) List<@Valid @NotNull Share> shares
        ) {
        }

        /** How much of the line is for one branch. */
        public record Share(
                @NotBlank @Size(max = 36) String branchId,
                @NotNull @Positive BigDecimal quantity
        ) {
        }
    }

    public record CentralOrderResponse(String purchaseOrderId, String poNumber, int lines, int branchesToSupply) {
    }

    /** An order whose delivery still has to be shared out to other branches. */
    public record PendingSplit(
            String purchaseOrderId,
            String poNumber,
            String status,
            String receivingBranchId,
            boolean received,
            List<SplitLine> lines
    ) {
    }

    public record SplitLine(String itemId, String itemName, String branchId, BigDecimal quantity) {
    }

    public record SplitResult(List<String> transferIds) {
    }

    // ── Schemes and claims ────────────────────────────────────────────

    public record SchemeRequest(
            @NotBlank @Size(max = 191) String name,
            @Size(max = 255) String contact,
            @Min(0) @Max(365) Integer paymentTermsDays,
            Boolean active
    ) {
    }

    public record SchemeResponse(
            String id,
            String name,
            String contact,
            Integer paymentTermsDays,
            boolean active,
            /** Claimed and not yet paid or rejected. */
            BigDecimal outstanding,
            int openClaims,
            /** The customer that stands for the scheme at the till; null until the scheme is saved again. */
            String creditCustomerId,
            /** What is on the scheme's tab now, from the credit ledger; null when it has no tab. */
            BigDecimal tabBalance
    ) {
    }

    public record ClaimRequest(
            @NotBlank @Size(max = 36) String schemeId,
            @NotBlank @Size(max = 36) String recordId,
            @NotBlank @Size(max = 64) String memberNo,
            @Size(max = 64) String authorisationCode,
            @NotNull @DecimalMin("0.01") BigDecimal amountClaimed
    ) {
    }

    public record ClaimStatusRequest(
            @NotBlank @Pattern(regexp = "SUBMITTED|PAID|REJECTED") String status,
            /** Total the scheme has paid so far; needed for PAID. Less than the claim leaves it part-paid. */
            @DecimalMin("0") BigDecimal amountPaid,
            @Size(max = 255) String note
    ) {
    }

    public record ClaimResponse(
            String id,
            String schemeId,
            String schemeName,
            String recordId,
            String saleId,
            Long receiptNo,
            String branchId,
            String patientName,
            String prescriberName,
            String medicines,
            String memberNo,
            String authorisationCode,
            BigDecimal amountClaimed,
            BigDecimal amountPaid,
            String status,
            String statusNote,
            Instant createdAt,
            Instant submittedAt,
            Instant settledAt,
            long ageDays
    ) {
    }
}
