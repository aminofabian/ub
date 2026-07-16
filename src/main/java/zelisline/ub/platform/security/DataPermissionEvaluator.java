package zelisline.ub.platform.security;

import java.io.Serializable;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.inventory.application.InventoryRoleAccessService;

/**
 * Bridges Spring EL {@code hasPermission(null, 'users.create')} to the
 * permissions-as-data model (PHASE_1_PLAN.md §2.2).
 */
@Component
public class DataPermissionEvaluator implements PermissionEvaluator {

    private final ObjectProvider<RequestPermissionService> permissionService;
    private final ObjectProvider<InventoryRoleAccessService> inventoryRoleAccessService;

    public DataPermissionEvaluator(
            ObjectProvider<RequestPermissionService> permissionService,
            ObjectProvider<InventoryRoleAccessService> inventoryRoleAccessService
    ) {
        this.permissionService = permissionService;
        this.inventoryRoleAccessService = inventoryRoleAccessService;
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
            if (authentication.getPrincipal() instanceof SupplierPrincipal supplier) {
                return zelisline.ub.marketplace.domain.SupplierUserRoles.permissionsFor(supplier.roleKey())
                        .contains(perm);
            }
            return false;
        }
        RequestPermissionService permissions = permissionService.getObject();
        if (permissions.hasPermission(tenant.roleId(), perm)) {
            return true;
        }
        InventoryRoleAccessService inventoryAccess = inventoryRoleAccessService.getObject();
        if ("inventory.write".equals(perm)) {
            return inventoryAccess.grantsDelegatedInventoryWrite(
                    tenant.businessId(),
                    tenant.roleId()
            );
        }
        if ("inventory.read".equals(perm)) {
            return inventoryAccess.grantsDelegatedInventoryRead(
                    tenant.businessId(),
                    tenant.roleId()
            );
        }
        if ("suppliers.write".equals(perm)) {
            return inventoryAccess.grantsDelegatedSupplierWrite(
                    tenant.businessId(),
                    tenant.roleId()
            );
        }
        if ("catalog.items.link_suppliers".equals(perm)) {
            return inventoryAccess.grantsDelegatedLinkSuppliers(
                    tenant.businessId(),
                    tenant.roleId()
            );
        }
        if ("purchasing.path_b.write".equals(perm)) {
            return inventoryAccess.grantsDelegatedPathBWrite(
                    tenant.businessId(),
                    tenant.roleId()
            );
        }
        return false;
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
