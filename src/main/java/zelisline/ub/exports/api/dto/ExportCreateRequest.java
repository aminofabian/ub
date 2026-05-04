package zelisline.ub.exports.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Phase 7 Slice 6 — enqueue body for {@code export_jobs}. */
public record ExportCreateRequest(
        @NotBlank String reportKey,
        @NotBlank @Pattern(regexp = "(?i)csv|xlsx") String format,
        @NotNull LocalDate from,
        @NotNull LocalDate to,
        String branchId
) {
}
