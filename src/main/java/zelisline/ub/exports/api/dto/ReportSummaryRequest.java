package zelisline.ub.exports.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Build a report summary for preview. from/to are required (inventory ignores them, but keeps one shape). */
public record ReportSummaryRequest(
        @NotBlank String reportKey,
        @NotNull LocalDate from,
        @NotNull LocalDate to,
        String branchId,
        String categoryId,
        String itemTypeId
) {
}
