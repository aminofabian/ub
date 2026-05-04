package zelisline.ub.tenancy.infrastructure;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import zelisline.ub.tenancy.domain.TenantStatus;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Resolves the request {@code Host} header to a tenant {@code business_id} before
 * Spring Security and authentication run.
 *
 * <p>Aligns with the {@code DomainBusinessResolverFilter} stage in
 * {@code docs/PHASE_1_PLAN.md} §1.4. On unknown hosts a {@code 404
 * application/problem+json} with type {@code urn:problem:tenant-not-found} is
 * returned — a {@code 401} would leak tenant existence.
 *
 * <p>Paths that are definitionally <em>not</em> tenant-scoped bypass the
 * filter entirely (super-admin platform ops, slug-in-URL public endpoints,
 * health/docs, webhooks). Without this, a prod host that isn't a tenant
 * mapping (e.g. {@code palmart.co.ke} on the admin app) would 404 every
 * super-admin login and health probe.
 *
 * <p>Tenant requests reaching the platform apex (e.g. {@code palmart.co.ke}
 * with no domain mapping) are forwarded when the client asserts a tenant
 * via {@code X-Tenant-Id}. Auth controllers re-resolve the tenant from that
 * header, so this preserves "no host enumeration" while letting tenants log
 * in from the platform login form.
 */
public class DomainBusinessResolverFilter extends OncePerRequestFilter {

    private static final String PROBLEM_TYPE = "urn:problem:tenant-not-found";
    private static final String PROBLEM_TYPE_NOT_ACTIVE = "urn:problem:tenant-not-active";

    private static final String X_TENANT_ID_HEADER = "X-Tenant-Id";

    private static final Set<String> BUILTIN_HOSTS_WITHOUT_MAPPING = Set.of(
            "localhost",
            "127.0.0.1",
            "::1"
    );

    private static final List<String> NON_TENANT_PATH_PREFIXES = List.of(
            "/api/v1/super-admin/",
            "/api/v1/public/",
            "/actuator/",
            "/api/v1/openapi",
            "/v3/api-docs",
            "/swagger-ui",
            "/webhooks/"
    );

    private final DomainMappingRepository domainMappingRepository;
    private final BusinessRepository businessRepository;
    private final ObjectMapper objectMapper;
    private final Set<String> hostsWithoutMapping;

    public DomainBusinessResolverFilter(
            DomainMappingRepository domainMappingRepository,
            BusinessRepository businessRepository
    ) {
        this(domainMappingRepository, businessRepository, new ObjectMapper(), List.of());
    }

    public DomainBusinessResolverFilter(
            DomainMappingRepository domainMappingRepository,
            BusinessRepository businessRepository,
            ObjectMapper objectMapper
    ) {
        this(domainMappingRepository, businessRepository, objectMapper, List.of());
    }

    /**
     * @param platformHosts hostnames that host the platform itself (e.g. the
     *     API base URL like {@code kiosk.zelisline.com}, or the admin login
     *     apex). Treated like {@code localhost}: the filter forwards the
     *     request without a resolved tenant, leaving controllers to honour
     *     {@code X-Tenant-Id} or fall back to public/non-tenant routes.
     */
    public DomainBusinessResolverFilter(
            DomainMappingRepository domainMappingRepository,
            BusinessRepository businessRepository,
            ObjectMapper objectMapper,
            Collection<String> platformHosts
    ) {
        this.domainMappingRepository = domainMappingRepository;
        this.businessRepository = businessRepository;
        this.objectMapper = objectMapper;
        Set<String> merged = new HashSet<>(BUILTIN_HOSTS_WITHOUT_MAPPING);
        if (platformHosts != null) {
            for (String h : platformHosts) {
                if (h == null) continue;
                String normalized = TenantHostParsing.hostnameOnly(h);
                if (normalized != null && !normalized.isBlank()) {
                    merged.add(normalized.toLowerCase(Locale.ROOT));
                }
            }
        }
        this.hostsWithoutMapping = Set.copyOf(merged);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) {
            return false;
        }
        for (String prefix : NON_TENANT_PATH_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String serverHost = TenantHostParsing.hostnameOnly(request.getServerName());
        String lookupHost = resolveLookupHost(serverHost, request.getHeader("X-Tenant-Host"));

        if (lookupHost == null || hostsWithoutMapping.contains(lookupHost.toLowerCase(Locale.ROOT))) {
            filterChain.doFilter(request, response);
            return;
        }

        var mapping = domainMappingRepository.findByDomainAndActiveTrue(lookupHost);
        if (mapping.isPresent()) {
            String businessId = mapping.get().getBusinessId();
            TenantStatus status = businessRepository.findTenantStatusById(businessId)
                    .orElse(TenantStatus.ACTIVE);
            if (status != TenantStatus.ACTIVE) {
                writeTenantNotActive(response, status);
                return;
            }
            request.setAttribute(TenantRequestAttributes.BUSINESS_ID, businessId);
            filterChain.doFilter(request, response);
            return;
        }

        if (hasExplicitTenantId(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        writeTenantNotFound(response, lookupHost);
    }

    private static boolean hasExplicitTenantId(HttpServletRequest request) {
        String value = request.getHeader(X_TENANT_ID_HEADER);
        return value != null && !value.isBlank();
    }

    private String resolveLookupHost(String serverHost, String tenantHostHeader) {
        if (serverHost != null && !hostsWithoutMapping.contains(serverHost.toLowerCase(Locale.ROOT))) {
            return serverHost;
        }
        return TenantHostParsing.hostnameOnly(tenantHostHeader);
    }

    private void writeTenantNotFound(HttpServletResponse response, String host) throws IOException {
        Map<String, Object> body = problemBody(HttpStatus.NOT_FOUND, PROBLEM_TYPE, "Tenant not found",
                "No active tenant mapping found for host: " + host);
        writeProblem(response, HttpStatus.NOT_FOUND, body);
    }

    private void writeTenantNotActive(HttpServletResponse response, TenantStatus status) throws IOException {
        Map<String, Object> body = problemBody(HttpStatus.LOCKED, PROBLEM_TYPE_NOT_ACTIVE, "Tenant not active",
                "Tenant is " + status.name().toLowerCase() + " and cannot accept authenticated traffic");
        body.put("tenantStatus", status.name());
        writeProblem(response, HttpStatus.LOCKED, body);
    }

    private static Map<String, Object> problemBody(HttpStatus status, String type, String title, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        return body;
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, Map<String, Object> body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
