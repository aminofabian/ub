package zelisline.ub.identity.api.dto;

public record AuthUserResponse(
        String id,
        String email,
        String name,
        String businessId,
        String branchId,
        String roleId,
        String status
) {
}
