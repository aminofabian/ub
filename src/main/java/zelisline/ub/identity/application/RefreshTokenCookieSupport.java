package zelisline.ub.identity.application;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HttpOnly refresh-token cookie ({@value #COOKIE_NAME}) scoped to {@code /api/v1/auth}.
 * When enabled, login/refresh responses omit the refresh token from JSON and set this cookie instead.
 */
@Component
public class RefreshTokenCookieSupport {

    public static final String COOKIE_NAME = "ub.refresh";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final boolean enabled;
    private final String domain;
    private final boolean secure;
    private final long maxAgeSeconds;

    public RefreshTokenCookieSupport(
            @Value("${app.auth.refresh-token-cookie-enabled:true}") boolean enabled,
            @Value("${app.auth.refresh-cookie-domain:}") String domain,
            @Value("${app.auth.refresh-cookie-secure:false}") boolean secure,
            @Value("${app.jwt.refresh-ttl-days:30}") long refreshTtlDays
    ) {
        this.enabled = enabled;
        this.domain = domain == null ? "" : domain.trim();
        this.secure = secure;
        this.maxAgeSeconds = Math.max(1, refreshTtlDays) * 24 * 60 * 60;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<String> read(HttpServletRequest request) {
        if (!enabled || request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }

    public HttpHeaders cookieHeaders(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        if (!enabled || refreshToken == null || refreshToken.isBlank()) {
            return headers;
        }
        headers.add(HttpHeaders.SET_COOKIE, buildCookie(refreshToken, maxAgeSeconds).toString());
        return headers;
    }

    public HttpHeaders clearCookieHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (!enabled) {
            return headers;
        }
        headers.add(HttpHeaders.SET_COOKIE, buildCookie("", 0).toString());
        return headers;
    }

    private ResponseCookie buildCookie(String value, long maxAgeSec) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(Duration.ofSeconds(maxAgeSec));
        if (!domain.isEmpty()) {
            builder.domain(domain);
        }
        return builder.build();
    }
}
