package zelisline.ub.platform.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

public final class CurrentSupplierUser {

    private CurrentSupplierUser() {
    }

    public static SupplierPrincipal require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        Object principal = authentication.getPrincipal();
        if (!(principal instanceof SupplierPrincipal sp)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Supplier portal access required");
        }
        return sp;
    }
}
