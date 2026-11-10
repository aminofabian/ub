package zelisline.ub.exports.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

/** Create/update a report-delivery subscription. */
public record ReportSubscriptionRequest(
        @NotBlank String reportKey,
        @Pattern(regexp = "(?i)csv|xlsx|pdf") String format,
        @NotBlank @Pattern(regexp = "(?i)daily|weekly|monthly") String frequency,
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$") String sendTime,
        Integer dayOfWeek,
        Integer dayOfMonth,
        @NotEmpty List<String> channels,
        String recipientEmail,
        String branchId,
        String categoryId,
        Boolean active
) {
}
