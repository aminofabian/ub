package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload for {@code POST /api/v1/users}.
 *
 * <p>Exactly one credential path must be chosen: provide a {@code password}, a
 * {@code pin}, or set {@code sendInvite=true} to email the user a link where
 * they set their own password. PIN is 4–6 digits; longer values are rejected so
 * we do not accidentally store something resembling a password as a PIN.
 */
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 191) String email,
        @NotBlank @Size(max = 255) String name,
        @Size(max = 50) String phone,
        @NotBlank @Size(max = 36) String roleId,
        @Size(max = 36) String branchId,
        @Size(max = 32) String status,
        @Size(min = 8, max = 191) String password,
        @Pattern(regexp = "\\d{4,6}", message = "PIN must be 4 to 6 digits") String pin,
        Boolean sendInvite
) {
}
