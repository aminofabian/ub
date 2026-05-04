package zelisline.ub.platform.security;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.ApiKeyAuthService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Authenticates {@code X-API-Key} or {@code Authorization: Bearer kpos_*} integration tokens.
 * Runs after {@link JwtAuthenticationFilter}; tenant must match host / {@code X-Tenant-Id}.
 */
@RequiredArgsConstructor
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String PROBLEM_BASE = "urn:problem:";
    private static final String HDR_API_KEY = "X-API-Key";

    /** Touch {@code last_used_at} at most once per key per interval (ms). */
    private static final long LAST_USED_MIN_INTERVAL_MS = 120_000;
    private static final ConcurrentHashMap<String, Long> LAST_TOUCH_MS_BY_KEY = new ConcurrentHashMap<>();

    private final ApiKeyAuthService apiKeyAuthService;
    private final InvalidApiKeyIpRateLimiter invalidApiKeyIpRateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        String raw = extractRawApiKey(request);
        if (raw == null || raw.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = ClientIpResolver.resolve(request);
        if (invalidApiKeyIpRateLimiter.isBlocked(clientIp)) {
            writeProblem(response, HttpStatus.TOO_MANY_REQUESTS, "Too many invalid API key attempts", "rate-limited");
            response.setHeader("Retry-After", "60");
            return;
        }

        var principalOpt = apiKeyAuthService.authenticateRawToken(raw);
        if (principalOpt.isEmpty()) {
            invalidApiKeyIpRateLimiter.recordFailure(clientIp);
            writeProblem(response, HttpStatus.UNAUTHORIZED, "Invalid or revoked API key", "unauthorized");
            return;
        }
        ApiKeyPrincipal principal = principalOpt.get();

        final String resolvedTenant;
        try {
            resolvedTenant = TenantRequestIds.resolveBusinessId(request);
        } catch (ResponseStatusException ex) {
            HttpStatus st = HttpStatus.resolve(ex.getStatusCode().value());
            if (st == null) {
                st = HttpStatus.BAD_REQUEST;
            }
            writeProblem(response, st,
                    ex.getReason() != null ? ex.getReason() : "Bad request", "bad-request");
            return;
        }

        if (!principal.businessId().equals(resolvedTenant)) {
            writeProblem(response, HttpStatus.FORBIDDEN,
                    "API key tenant does not match resolved host tenant", "forbidden");
            return;
        }

        invalidApiKeyIpRateLimiter.clear(clientIp);
        maybeTouchLastUsed(principal.apiKeyId());

        var authentication = new ApiKeyAuthenticationToken(principal);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void maybeTouchLastUsed(String apiKeyId) {
        long now = Instant.now().toEpochMilli();
        boolean[] touch = {false};
        LAST_TOUCH_MS_BY_KEY.compute(apiKeyId, (id, prev) -> {
            if (prev != null && now - prev < LAST_USED_MIN_INTERVAL_MS) {
                return prev;
            }
            touch[0] = true;
            return now;
        });
        if (touch[0]) {
            apiKeyAuthService.touchLastUsed(apiKeyId);
        }
    }

    private static String extractRawApiKey(HttpServletRequest request) {
        String header = request.getHeader(HDR_API_KEY);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String auth = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = auth.substring(7).trim();
            if (token.startsWith("kpos_")) {
                return token;
            }
        }
        return null;
    }

    private void writeProblem(HttpServletResponse response, HttpStatus status, String title, String slug)
            throws IOException {
        ProblemDetail body = ProblemDetail.forStatus(status.value());
        body.setTitle(title);
        body.setType(URI.create(PROBLEM_BASE + slug));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
