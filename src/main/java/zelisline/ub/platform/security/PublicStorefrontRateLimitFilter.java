package zelisline.ub.platform.security;

import java.io.IOException;
import java.net.URI;

import org.springframework.http.HttpMethod;
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
import lombok.RequiredArgsConstructor;

/**
 * Rate limits {@code GET /api/v1/public/**} by client IP + business slug (Phase 15).
 */
@RequiredArgsConstructor
public class PublicStorefrontRateLimitFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/v1/public/businesses/";
    private static final String PROBLEM_BASE = "urn:problem:";

    private final PublicStorefrontIpRateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/public/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String slug = storefrontSlugFromPath(path);
        String ip = clientIp(request);
        String key = ip + "|" + slug;

        if (!rateLimiter.tryConsume(key)) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            body.setTitle("Too many requests");
            body.setType(URI.create(PROBLEM_BASE + "rate-limited"));
            body.setDetail("Storefront catalog rate limit exceeded. Try again shortly.");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", "60");
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }

        filterChain.doFilter(request, response);
    }

    static String storefrontSlugFromPath(String path) {
        if (!path.startsWith(PREFIX)) {
            return "";
        }
        int start = PREFIX.length();
        int slash = path.indexOf('/', start);
        if (slash < 0) {
            return path.substring(start);
        }
        return path.substring(start, slash);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
