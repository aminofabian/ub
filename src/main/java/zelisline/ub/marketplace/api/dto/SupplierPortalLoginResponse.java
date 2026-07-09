package zelisline.ub.marketplace.api.dto;

public record SupplierPortalLoginResponse(
        String accessToken,
        String userId,
        String marketplaceSupplierId,
        String email,
        String name
) {
}
