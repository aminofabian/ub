package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * PIN till login. {@code branchId} is optional — when omitted, the server uses the
 * staff member's assigned branch.
 */
public record LoginPinRequest(
        @NotBlank @Email @Size(max = 191) String email,
        @Size(max = 36) String branchId,
        @NotBlank @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits") String pin
) {
}
