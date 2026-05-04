package zelisline.ub.identity.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Response for {@code POST /api/v1/auth/register}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterResponse(
        String userId,
        String email,
        String status,
        String verificationUrl
) {
}
