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
 * Applies {@link LoginIpRateLimiter} to tenant login endpoints only.
 */
@RequiredArgsConstructor
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private static final String PROBLEM_BASE = "urn:problem:";
    private final LoginIpRateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (!path.endsWith("/api/v1/auth/login") && !path.endsWith("/api/v1/auth/login-pin")) {
            filterChain.doFilter(request, response);
            return;
        }

        String ip = clientIp(request);
        if (!rateLimiter.tryConsume(ip)) {
            ProblemDetail body = ProblemDetail.forStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            body.setTitle("Too many login attempts");
            body.setType(URI.create(PROBLEM_BASE + "rate-limited"));
            body.setDetail("Try again in a minute.");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
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
