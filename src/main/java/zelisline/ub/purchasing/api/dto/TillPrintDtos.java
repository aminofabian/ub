package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class TillPrintDtos {

    private TillPrintDtos() {
    }

    public record DispatchTillPrintRequest(
            @NotBlank String kind,
            String branchId,
            @NotNull List<String> targetUserIds,
            @NotNull @Valid TillPrintSlip slip
    ) {
    }

    public record TillPrintSlip(
            @NotBlank String reference,
            String supplierName,
            String businessName,
            String branchName,
            String placedByName,
            String currency,
            @NotNull @Valid List<TillPrintSlipLine> lines
    ) {
    }

    public record TillPrintSlipLine(
            @NotBlank String name,
            @NotNull BigDecimal qty,
            BigDecimal unitCost,
            BigDecimal lineTotal
    ) {
    }

    public record TillPrintCashierResponse(String id, String name) {
    }

    public record TillPrintTargetStatus(String userId, String name, boolean online) {
    }

    public record DispatchTillPrintResponse(List<String> jobIds, List<TillPrintTargetStatus> tills) {
    }

    public record TillPrintPendingResponse(
            String id,
            String kind,
            String reference,
            Instant createdAt,
            TillPrintSlip slip
    ) {
    }

    public record TillPrintClaimResponse(String id, String kind, TillPrintSlip slip) {
    }
}
