package zelisline.ub.identity.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Disconnecting Google is a credential change, so it requires the account
 * password (a Google-only owner whose password is a hidden system hash cannot
 * satisfy this and is told to set a password first).
 */
public record UnlinkOAuthRequest(
        @NotBlank String password
) {
}
