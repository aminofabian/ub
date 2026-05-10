package zelisline.ub.sales.api.dto;

import jakarta.validation.constraints.NotBlank;

public record ApproveDrawoutRequest(
        @NotBlank String approvalMethod
) {
}
