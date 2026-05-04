package zelisline.ub.platform.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import zelisline.ub.tenancy.api.TenantRequestIds;

public final class CurrentTenantUser {

    private CurrentTenantUser() {
    }

    /**
     * Ensures the caller is authenticated as a tenant user or integration API key and that
     * {@code business_id} matches the resolved host tenant.
     */
    public static void require(HttpServletRequest request) {
        Object p = requireAuthenticatedPrincipal(request);
        if (p instanceof TenantPrincipal tp) {
            TenantRequestIds.requireMatchingTenant(request, tp.businessId());
            return;
        }
        if (p instanceof ApiKeyPrincipal akp) {
            TenantRequestIds.requireMatchingTenant(request, akp.businessId());
            return;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
    }

    /**
     * Interactive JWT / test-auth sessions only — rejects integration API keys.
     */
    public static TenantPrincipal requireHuman(HttpServletRequest request) {
        Object p = requireAuthenticatedPrincipal(request);
        if (p instanceof TenantPrincipal tp) {
            TenantRequestIds.requireMatchingTenant(request, tp.businessId());
            return tp;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This action requires a signed-in user.");
    }

    /**
     * {@code users.id} for people, or {@link ApiKeyPrincipal#apiKeyId()} for machine tokens.
     */
    public static String auditActorId(HttpServletRequest request) {
        Object p = requireAuthenticatedPrincipal(request);
        if (p instanceof TenantPrincipal tp) {
            TenantRequestIds.requireMatchingTenant(request, tp.businessId());
            return tp.userId();
        }
        if (p instanceof ApiKeyPrincipal akp) {
            TenantRequestIds.requireMatchingTenant(request, akp.businessId());
            return akp.apiKeyId();
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
    }

    private static Object requireAuthenticatedPrincipal(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return authentication.getPrincipal();
    }
}
