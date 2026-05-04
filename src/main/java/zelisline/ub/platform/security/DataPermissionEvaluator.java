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
        if (authentication == null) {
            return false;
        }
        String perm = toPermissionKey(permission);
        if (perm == null) {
            return false;
        }
        if (authentication.getPrincipal() instanceof ApiKeyPrincipal apiKey) {
            return apiKey.scopes() != null && apiKey.scopes().contains(perm);
        }
        if (!(authentication.getPrincipal() instanceof TenantPrincipal tenant)) {
            return false;
        }
        return permissionService.getObject().hasPermission(tenant.roleId(), perm);
    }

    /**
     * Integration API keys reuse the same dotted permission strings as JWT roles.
     * SpEL occasionally surfaces non-{@link String} CharSequence wrappers.
     */
    private static String toPermissionKey(Object permission) {
        if (permission == null) {
            return null;
        }
        if (permission instanceof String s) {
            return s.isBlank() ? null : s;
        }
        if (permission instanceof CharSequence cs) {
            String raw = cs.toString();
            return raw.isBlank() ? null : raw;
        }
        return null;
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
