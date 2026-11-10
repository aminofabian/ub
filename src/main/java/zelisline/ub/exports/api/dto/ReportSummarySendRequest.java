package zelisline.ub.exports.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Send a report summary to the owner's verified alert phone. channel optional: sms|whatsapp|both|auto. */
public record ReportSummarySendRequest(
        @NotBlank String reportKey,
        @NotNull LocalDate from,
        @NotNull LocalDate to,
        String branchId,
        String categoryId,
        String itemTypeId,
        @Pattern(regexp = "(?i)sms|whatsapp|both|auto") String channel
) {
}
