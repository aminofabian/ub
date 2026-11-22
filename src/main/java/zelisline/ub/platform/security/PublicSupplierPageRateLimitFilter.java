package zelisline.ub.platform.security;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

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
 * Rate limits {@code /api/v1/public/suppliers/**} (ADR 0038, P0.2). These endpoints take no login:
 * one sends an SMS code, three check a PIN, one writes a complaint, and the reads name a shop's
 * supplier.
 *
 * <ul>
 *   <li>PIN checks (unlock, unseal, seal): 5 a minute per caller and page, and 10 a minute per page
 *       from everyone together, so a PIN cannot be guessed by spreading the tries across addresses.</li>
 *   <li>SMS code: 3 a minute per caller, 5 a minute per page.</li>
 *   <li>Other writes: 10 a minute per caller. Reads: 120 a minute per caller.</li>
 * </ul>
 */
@RequiredArgsConstructor
public class PublicSupplierPageRateLimitFilter extends OncePerRequestFilter {

    private static final String PATH_PREFIX = "/api/v1/public/suppliers/";
    private static final String PROBLEM_BASE = "urn:problem:";

    private final PublicSupplierPageRateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        String ip = clientIp(request);
        String page = path.toLowerCase(Locale.ROOT);
        boolean allowed;
        if (!HttpMethod.POST.matches(request.getMethod())) {
            allowed = rateLimiter.tryConsume("read:" + ip, 120);
        } else if (page.endsWith("/page-seal/unlock")
                || page.endsWith("/page-seal/unseal")
                || page.endsWith("/page-seal/seal")) {
            allowed = rateLimiter.tryConsume("pin:" + ip + ":" + page, 5)
                    && rateLimiter.tryConsume("pin-page:" + page, 10);
        } else if (page.endsWith("/page-seal/send-code")) {
            allowed = rateLimiter.tryConsume("sms:" + ip, 3)
                    && rateLimiter.tryConsume("sms-page:" + page, 5);
        } else {
            allowed = rateLimiter.tryConsume("write:" + ip, 10);
        }
        if (!allowed) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS);
            body.setTitle("Too many requests");
            body.setType(URI.create(PROBLEM_BASE + "rate-limited"));
            body.setDetail("Too many tries. Wait a minute and try again.");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", "60");
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
