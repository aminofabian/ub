package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload for checkout email routing (signup vs sign-in). */
public record EmailLookupRequest(
        @NotBlank @Email @Size(max = 191) String email
) {
}
