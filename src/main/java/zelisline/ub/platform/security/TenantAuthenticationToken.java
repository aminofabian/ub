package zelisline.ub.platform.security;

import java.util.Collections;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Pre-JWT authentication token holding a {@link TenantPrincipal}.
 *
 * <p>No {@code GrantedAuthority} entries — {@code hasPermission(...)} is resolved
 * exclusively via {@link DataPermissionEvaluator} + the request-scoped cache
 * (PHASE_1_PLAN.md §2.2).
 */
public class TenantAuthenticationToken extends AbstractAuthenticationToken {

    private final TenantPrincipal principal;

    public TenantAuthenticationToken(TenantPrincipal principal) {
        super(Collections.emptyList());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public TenantPrincipal getPrincipal() {
        return principal;
    }
}
