package zelisline.ub.platform.security;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Long-lived bearer secret for scraping {@code /actuator/prometheus} and
 * {@code /actuator/metrics} from Prometheus / Grafana.
 *
 * <p>Super-admin JWTs expire (default 15 min) and cannot be refreshed by a
 * scraper, so a deployment that wants continuous scraping can set
 * {@code app.actuator.prometheus-token} (e.g. {@code openssl rand -hex 24}) and
 * present it as {@code Authorization: Bearer <token>}. The token is accepted
 * only for the two metrics paths; it is not a general API credential.
 *
 * <p>Runs <em>before</em> {@link JwtAuthenticationFilter} and hides the
 * Authorization header from it after authenticating, so a plain (non-JWT)
 * bearer secret is not misparsed as a JWT. When the property is blank the
 * filter is a no-op and the metrics paths fall back to the normal
 * {@code ROLE_SUPER_ADMIN} JWT rule in {@code SecurityConfig}.
 */
@Component
public class MetricsBearerTokenFilter extends OncePerRequestFilter {

    private static final String METRICS_PREFIX_1 = "/actuator/prometheus";
    private static final String METRICS_PREFIX_2 = "/actuator/metrics";

    private final String expectedToken;

    public MetricsBearerTokenFilter(
            @Value("${app.actuator.prometheus-token:}") String expectedToken
    ) {
        this.expectedToken = expectedToken == null ? "" : expectedToken.trim();
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        if (expectedToken.isEmpty() || !isMetricsPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        // A real super-admin JWT (or an already-authenticated context) wins over the secret.
        if (SecurityContextHolder.getContext().getAuthentication() != null
                && SecurityContextHolder.getContext().getAuthentication().isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String presented = header.substring(7).trim();
            if (!presented.isEmpty() && constantTimeEquals(presented, expectedToken)) {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                "metrics-token",
                                "",
                                List.of(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))
                        )
                );
                // Stop the JWT filter from misparsing the secret as a JWT.
                filterChain.doFilter(new AuthorizationHeaderHiddenRequest(request), response);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isMetricsPath(String uri) {
        return uri != null
                && (uri.startsWith(METRICS_PREFIX_1) || uri.startsWith(METRICS_PREFIX_2));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }

    /** Presents the request to the rest of the chain without its Authorization header. */
    private static final class AuthorizationHeaderHiddenRequest extends HttpServletRequestWrapper {

        private static final String AUTHORIZATION = "authorization";

        AuthorizationHeaderHiddenRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return AUTHORIZATION.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return AUTHORIZATION.equalsIgnoreCase(name)
                    ? Collections.emptyEnumeration()
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Enumeration<String> names = super.getHeaderNames();
            List<String> filtered = Collections.list(names).stream()
                    .filter(name -> !AUTHORIZATION.equalsIgnoreCase(name))
                    .toList();
            return Collections.enumeration(filtered);
        }
    }
}
