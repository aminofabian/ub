package zelisline.ub.identity.api.dto;

public record SuperAdminLoginResponse(
        String accessToken,
        String superAdminId,
        String email,
        String name
) {
}
