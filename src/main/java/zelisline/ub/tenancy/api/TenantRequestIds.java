package zelisline.ub.tenancy.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import zelisline.ub.tenancy.infrastructure.TenantRequestAttributes;

/**
 * Resolves {@code business_id} for HTTP handlers from the domain resolver
 * attribute (request {@code Host} or {@code X-Tenant-Host} when Host is bare
 * localhost) or {@code X-Tenant-Id} (explicit UUID dev path).
 *
 * <p>Authenticated requests never need either: the authentication filters call
 * {@link #bindBusinessId} with the tenant carried by the validated credential
 * (JWT {@code business_id} claim, API key row), so every downstream handler
 * resolves the same tenant regardless of which host the client came in on.
 */
public final class TenantRequestIds {

    private static final Logger log = LoggerFactory.getLogger(TenantRequestIds.class);

    private TenantRequestIds() {
    }

    /**
     * Publishes the tenant of an already-verified credential onto the request so
     * later {@link #resolveBusinessId} calls succeed without a mapped host.
     */
    public static void bindBusinessId(HttpServletRequest request, String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return;
        }
        request.setAttribute(TenantRequestAttributes.BUSINESS_ID, businessId.trim());
    }

    /** @return resolved tenant, or {@code null} when the request carries no tenant context. */
    public static String resolveBusinessIdOrNull(HttpServletRequest request) {
        Object fromResolver = request.getAttribute(TenantRequestAttributes.BUSINESS_ID);
        if (fromResolver instanceof String value && !value.isBlank()) {
            log.debug("[TenantIds] resolved from request attribute: {}", value);
            return value.trim();
        }

        String fromHeader = request.getHeader("X-Tenant-Id");
        if (fromHeader != null && !fromHeader.isBlank()) {
            String trimmed = fromHeader.trim();
            log.debug("[TenantIds] resolved from X-Tenant-Id header: {}", trimmed);
            return trimmed;
        }

        return null;
    }

    public static String resolveBusinessId(HttpServletRequest request) {
        String resolved = resolveBusinessIdOrNull(request);
        if (resolved != null) {
            return resolved;
        }

        log.warn("[TenantIds] NO tenant context - no attribute and no X-Tenant-Id header. URI={} serverName={}",
                request.getRequestURI(), request.getServerName());
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
        String resolved = resolveBusinessIdOrNull(request);
        if (resolved == null) {
            // The credential itself is the tenant of record; a missing host
            // mapping must not fail an otherwise authenticated request.
            bindBusinessId(request, principalBusinessId);
            return principalBusinessId;
        }
        if (!resolved.equals(principalBusinessId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Principal tenant does not match resolved host tenant"
            );
        }
        return resolved;
    }
}
