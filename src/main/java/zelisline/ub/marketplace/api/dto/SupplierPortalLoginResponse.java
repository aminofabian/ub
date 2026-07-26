package zelisline.ub.marketplace.api.dto;

public record SupplierPortalLoginResponse(
        String accessToken,
        String sessionId,
        String userId,
        String marketplaceSupplierId,
        String email,
        String phone,
        String name
) {
}
