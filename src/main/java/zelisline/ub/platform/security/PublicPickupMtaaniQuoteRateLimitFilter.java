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
 * Rate limits the anonymous Pickup Mtaani quote POST by client IP + slug, so a
 * storefront loop cannot burn the tenant's upstream key (scope §13). GET search
 * is already covered by {@link PublicStorefrontRateLimitFilter}.
 */
@RequiredArgsConstructor
public class PublicPickupMtaaniQuoteRateLimitFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/v1/public/businesses/";
    private static final String SUFFIX = "/pickup-mtaani/quote";
    private static final String PROBLEM_BASE = "urn:problem:";

    private final PublicStorefrontIpRateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!HttpMethod.POST.matches(request.getMethod())
                || !path.startsWith(PREFIX)
                || !path.endsWith(SUFFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String slug = slugFromPath(path);
        String key = clientIp(request) + "|" + slug;
        if (!rateLimiter.tryConsume(key)) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            body.setTitle("Too many requests");
            body.setType(URI.create(PROBLEM_BASE + "rate-limited"));
            body.setDetail("Delivery quote rate limit exceeded. Try again shortly.");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", "60");
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static String slugFromPath(String path) {
        int start = PREFIX.length();
        int slash = path.indexOf('/', start);
        return slash < 0 ? path.substring(start) : path.substring(start, slash);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
