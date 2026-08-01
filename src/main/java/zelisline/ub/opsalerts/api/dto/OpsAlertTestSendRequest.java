package zelisline.ub.opsalerts.api.dto;

import jakarta.validation.constraints.Size;

public record OpsAlertTestSendRequest(
        @Size(max = 32) String phone,
        @Size(max = 500) String message
) {
}
