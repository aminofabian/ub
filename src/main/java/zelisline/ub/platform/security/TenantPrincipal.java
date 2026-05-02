package zelisline.ub.platform.security;

/**
 * Authenticated tenant user identity in the Spring Security context.
 *
 * @param userId     Primary key of {@code users.id}
 * @param businessId Tenant id from the domain resolver (must match JWT {@code business_id})
 * @param roleId     {@code users.role_id} — drives permissions-as-data resolution
 * @param branchId   Optional {@code users.branch_id} from the token
 * @param accessJti  JWT id ({@code jti}) of the current access token; {@code null} for test header auth
 */
public record TenantPrincipal(
        String userId,
        String businessId,
        String roleId,
        String branchId,
        String accessJti
) {
    public TenantPrincipal(String userId, String businessId, String roleId) {
        this(userId, businessId, roleId, null, null);
    }
}
