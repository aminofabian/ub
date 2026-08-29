package zelisline.ub.payroll.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record StaffSmsBulkSendRequest(
        @NotEmpty List<@NotBlank @Size(max = 36) String> userIds,
        @NotBlank @Size(max = 64) String templateKey,
        @Size(max = 500) String bodyOverride
) {
}
