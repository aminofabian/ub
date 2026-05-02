package zelisline.ub.tenancy.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import zelisline.ub.tenancy.infrastructure.TenantRequestAttributes;

/**
 * Resolves {@code business_id} for HTTP handlers from the domain resolver
 * attribute (request {@code Host} or {@code X-Tenant-Host} when Host is bare
 * localhost) or {@code X-Tenant-Id} (explicit UUID dev path).
 */
public final class TenantRequestIds {

    private TenantRequestIds() {
    }

    public static String resolveBusinessId(HttpServletRequest request) {
        Object fromResolver = request.getAttribute(TenantRequestAttributes.BUSINESS_ID);
        if (fromResolver instanceof String value && !value.isBlank()) {
            return value;
        }

        String fromHeader = request.getHeader("X-Tenant-Id");
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader.trim();
        }

        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Tenant context missing. Provide mapped Host header or X-Tenant-Id."
        );
    }

    /**
     * Ensures the authenticated principal's tenant matches the resolved request tenant
     * (future JWT claim vs Host guard — PHASE_1_PLAN.md §1.4).
     */
    public static String requireMatchingTenant(HttpServletRequest request, String principalBusinessId) {
        String resolved = resolveBusinessId(request);
        if (!resolved.equals(principalBusinessId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Principal tenant does not match resolved host tenant"
            );
        }
        return resolved;
    }
}
