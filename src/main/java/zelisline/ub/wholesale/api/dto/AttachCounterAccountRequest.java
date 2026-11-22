package zelisline.ub.wholesale.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AttachCounterAccountRequest(
        @NotBlank @Size(max = 36) String accountId
) {
}
