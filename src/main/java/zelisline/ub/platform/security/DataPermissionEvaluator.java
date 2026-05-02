package zelisline.ub.platform.security;

import java.io.Serializable;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import zelisline.ub.identity.application.RequestPermissionService;

/**
 * Bridges Spring EL {@code hasPermission(null, 'users.create')} to the
 * permissions-as-data model (PHASE_1_PLAN.md §2.2).
 */
@Component
public class DataPermissionEvaluator implements PermissionEvaluator {

    private final ObjectProvider<RequestPermissionService> permissionService;

    public DataPermissionEvaluator(ObjectProvider<RequestPermissionService> permissionService) {
        this.permissionService = permissionService;
    }

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (authentication == null || !(permission instanceof String perm)) {
            return false;
        }
        if (!(authentication.getPrincipal() instanceof TenantPrincipal tenant)) {
            return false;
        }
        return permissionService.getObject().hasPermission(tenant.roleId(), perm);
    }

    @Override
    public boolean hasPermission(
            Authentication authentication,
            Serializable targetId,
            String targetType,
            Object permission
    ) {
        return false;
    }
}
