package zelisline.ub.platform.security;

import java.io.IOException;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Test / local-dev bridge: builds a {@link TenantAuthenticationToken} from
 * headers so {@code @PreAuthorize("hasPermission(...)")} integration tests can
 * run before a real JWT is minted.
 *
 * <p>Skipped when a {@code Bearer} access token is present (Slice 3 JWT wins) or
 * when the security context is already authenticated.
 *
 * <p>Enabled only when {@code app.security.test-auth.enabled=true}.
 */
public class TestAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_USER_ID = "X-Test-User-Id";
    public static final String HEADER_ROLE_ID = "X-Test-Role-Id";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing != null && existing.isAuthenticated()
                && !(existing instanceof AnonymousAuthenticationToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            filterChain.doFilter(request, response);
            return;
        }

        String userId = trimToNull(request.getHeader(HEADER_USER_ID));
        String roleId = trimToNull(request.getHeader(HEADER_ROLE_ID));

        if (userId == null && roleId == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (userId == null || roleId == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String businessId;
        try {
            businessId = TenantRequestIds.resolveBusinessId(request);
        } catch (ResponseStatusException ex) {
            response.setStatus(ex.getStatusCode().value());
            return;
        }

        TenantPrincipal principal = new TenantPrincipal(userId, businessId, roleId);
        SecurityContextHolder.getContext().setAuthentication(new TenantAuthenticationToken(principal));

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
