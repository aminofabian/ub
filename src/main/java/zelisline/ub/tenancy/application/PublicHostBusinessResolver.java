package zelisline.ub.tenancy.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.infrastructure.TenantHostParsing;
import zelisline.ub.tenancy.infrastructure.TenantRequestAttributes;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Resolves {@code business_id} for public host-scoped endpoints.
 *
 * <p>{@link zelisline.ub.tenancy.infrastructure.DomainBusinessResolverFilter} skips
 * {@code /api/v1/public/**}, so those handlers cannot rely on the request attribute alone.
 * This resolver falls back to {@code X-Tenant-Host} / {@code Host} domain mapping
 * (same source as the storefront) and then {@code X-Tenant-Id}.
 */
@Service
@RequiredArgsConstructor
public class PublicHostBusinessResolver {

    private static final Logger log = LoggerFactory.getLogger(PublicHostBusinessResolver.class);

    private final DomainMappingRepository domainMappingRepository;

    public String resolveOrThrow(HttpServletRequest request) {
        Object fromResolver = request.getAttribute(TenantRequestAttributes.BUSINESS_ID);
        if (fromResolver instanceof String value && !value.isBlank()) {
            return value.trim();
        }

        String fromHeader = request.getHeader("X-Tenant-Id");
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader.trim();
        }

        String host = firstHost(
                request.getHeader("X-Tenant-Host"),
                request.getHeader("X-Forwarded-Host"),
                request.getServerName());
        if (host != null) {
            var mapping = domainMappingRepository.findByDomainAndActiveTrue(host);
            if (mapping.isPresent()) {
                log.debug("[PublicHostBusiness] host={} businessId={}", host, mapping.get().getBusinessId());
                return mapping.get().getBusinessId();
            }
            log.warn("[PublicHostBusiness] no domain mapping for host={} uri={}", host, request.getRequestURI());
        } else {
            log.warn("[PublicHostBusiness] no host and no X-Tenant-Id uri={}", request.getRequestURI());
        }

        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Tab not found");
    }

    private static String firstHost(String... candidates) {
        for (String raw : candidates) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            // X-Forwarded-Host may be a comma list
            String first = raw.split(",")[0].trim();
            String host = TenantHostParsing.hostnameOnly(first);
            if (host != null && !host.isBlank()) {
                return host.toLowerCase();
            }
        }
        return null;
    }
}
