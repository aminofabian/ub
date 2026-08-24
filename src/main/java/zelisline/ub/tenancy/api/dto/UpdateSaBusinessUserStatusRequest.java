package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Super-admin body for changing a tenant user's status
 * ({@code PATCH /super-admin/businesses/{id}/users/{userId}/status}).
 *
 * <p>Accepts the lowercase wire form of {@link zelisline.ub.identity.domain.UserStatus}
 * ({@code active}, {@code invited}, {@code suspended}, {@code locked}).
 */
public record UpdateSaBusinessUserStatusRequest(
        @NotBlank @Size(max = 32) String status
) {
}
