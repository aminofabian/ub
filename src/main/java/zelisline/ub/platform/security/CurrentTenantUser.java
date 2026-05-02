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
     * Returns the authenticated {@link TenantPrincipal} and ensures its
     * {@code businessId} matches the host-resolved tenant.
     */
    public static TenantPrincipal require(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        if (!(authentication.getPrincipal() instanceof TenantPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        TenantRequestIds.requireMatchingTenant(request, principal.businessId());
        return principal;
    }
}
