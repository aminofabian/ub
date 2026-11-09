package zelisline.ub.exports.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Phase 7 Slice 6 — enqueue body for {@code export_jobs}.
 *
 * <p>Extended (report-downloads scope) with {@code categoryId} / {@code itemTypeId}
 * filters and a {@code pdf} output format for the sales-by-category report. The older
 * {@code sales_register} report ignores the two filter fields.</p>
 */
public record ExportCreateRequest(
        @NotBlank String reportKey,
        @NotBlank @Pattern(regexp = "(?i)csv|xlsx|pdf") String format,
        @NotNull LocalDate from,
        @NotNull LocalDate to,
        String branchId,
        String categoryId,
        String itemTypeId
) {
}
