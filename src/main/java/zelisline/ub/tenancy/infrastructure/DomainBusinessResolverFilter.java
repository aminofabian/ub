package zelisline.ub.tenancy.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * <p>When the host is not mapped but the client supplies an explicit
 * {@code X-Tenant-Id} header (platform admin host logging into a specific
 * tenant), the filter passes through; downstream
 * {@link zelisline.ub.tenancy.api.TenantRequestIds} already honors that
 * header and JWT-bound endpoints enforce principal/tenant match.
 */
public class DomainBusinessResolverFilter extends OncePerRequestFilter {

    private static final String PROBLEM_TYPE = "urn:problem:tenant-not-found";
    private static final String TENANT_ID_HEADER = "X-Tenant-Id";

    private static final Set<String> HOSTS_WITHOUT_MAPPING = Set.of(
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
    private final ObjectMapper objectMapper;

    public DomainBusinessResolverFilter(DomainMappingRepository domainMappingRepository) {
        this(domainMappingRepository, new ObjectMapper());
    }

    DomainBusinessResolverFilter(
            DomainMappingRepository domainMappingRepository,
            ObjectMapper objectMapper
    ) {
        this.domainMappingRepository = domainMappingRepository;
        this.objectMapper = objectMapper;
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

        if (lookupHost == null || HOSTS_WITHOUT_MAPPING.contains(lookupHost)) {
            filterChain.doFilter(request, response);
            return;
        }

        var mapping = domainMappingRepository.findByDomainAndActiveTrue(lookupHost);
        if (mapping.isEmpty()) {
            if (hasExplicitTenantIdHeader(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            writeTenantNotFound(response, lookupHost);
            return;
        }

        request.setAttribute(TenantRequestAttributes.BUSINESS_ID, mapping.get().getBusinessId());
        filterChain.doFilter(request, response);
    }

    private static String resolveLookupHost(String serverHost, String tenantHostHeader) {
        if (serverHost != null && !HOSTS_WITHOUT_MAPPING.contains(serverHost)) {
            return serverHost;
        }
        return TenantHostParsing.hostnameOnly(tenantHostHeader);
    }

    private static boolean hasExplicitTenantIdHeader(HttpServletRequest request) {
        String value = request.getHeader(TENANT_ID_HEADER);
        return value != null && !value.isBlank();
    }

    private void writeTenantNotFound(HttpServletResponse response, String host) throws IOException {
        ProblemDetail body = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        body.setTitle("Tenant not found");
        body.setType(URI.create(PROBLEM_TYPE));
        body.setDetail("No active tenant mapping found for host: " + host);

        response.setStatus(HttpStatus.NOT_FOUND.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
