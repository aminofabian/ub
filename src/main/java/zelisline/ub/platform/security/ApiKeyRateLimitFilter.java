package zelisline.ub.platform.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** Applies {@link ApiKeyRateLimiter} after an {@link ApiKeyPrincipal} is authenticated. */
@RequiredArgsConstructor
public class ApiKeyRateLimitFilter extends OncePerRequestFilter {

    private final ApiKeyRateLimiter apiKeyRateLimiter;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ApiKeyPrincipal ak) {
            if (!apiKeyRateLimiter.tryConsume(ak.apiKeyId())) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", "60");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
