package zelisline.ub.platform.security;

/**
 * Authenticated supplier portal user identity in the Spring Security context.
 *
 * @param userId                {@code supplier_users.id}
 * @param marketplaceSupplierId Platform supplier the user belongs to
 * @param roleKey               Drives supplier portal permission resolution
 */
public record SupplierPrincipal(
        String userId,
        String marketplaceSupplierId,
        String roleKey
) {
}
