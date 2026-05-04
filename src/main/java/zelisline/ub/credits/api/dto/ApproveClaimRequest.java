package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ApproveClaimRequest(
        @NotBlank @Pattern(regexp = "cash|mpesa", message = "channel must be 'cash' or 'mpesa'") String channel
) {
}
