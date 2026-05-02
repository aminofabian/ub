package zelisline.ub.identity.api.dto;

/** Response for {@code POST /api/v1/auth/register}. */
public record RegisterResponse(
        String userId,
        String email,
        String status
) {
}
