package zelisline.ub.identity.api.dto;

public record LoginResponse(
        String accessToken,
        String refreshToken,
        AuthUserResponse user
) {
}
