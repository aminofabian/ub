package zelisline.ub.identity.application;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HttpOnly refresh-token cookie ({@value #COOKIE_NAME}) scoped to {@code /api}
 * so both the BFF ({@code /api/v1/auth/*}) and Next helpers
 * ({@code /api/auth/restore-session}) receive it. The previous
 * {@code /api/v1/auth} path is cleared on every set so browsers do not keep a
 * stale second cookie.
 */
@Component
public class RefreshTokenCookieSupport {

    public static final String COOKIE_NAME = "ub.refresh";
    static final String COOKIE_PATH = "/api";
    static final String LEGACY_COOKIE_PATH = "/api/v1/auth";

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
        List<String> all = readAll(request);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * Every {@code ub.refresh} the browser sent. Host-only leftovers can shadow
     * a newer parent-domain cookie; callers must prefer a still-active row.
     */
    public List<String> readAll(HttpServletRequest request) {
        if (!enabled || request.getCookies() == null) {
            return List.of();
        }
        return Arrays.stream(request.getCookies())
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .toList();
    }

    public HttpHeaders cookieHeaders(String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        if (!enabled || refreshToken == null || refreshToken.isBlank()) {
            return headers;
        }
        headers.add(HttpHeaders.SET_COOKIE, buildCookie(refreshToken, maxAgeSeconds, COOKIE_PATH).toString());
        headers.add(HttpHeaders.SET_COOKIE, buildCookie("", 0, LEGACY_COOKIE_PATH).toString());
        appendHostOnlyClears(headers);
        return headers;
    }

    public HttpHeaders clearCookieHeaders() {
        HttpHeaders headers = new HttpHeaders();
        if (!enabled) {
            return headers;
        }
        headers.add(HttpHeaders.SET_COOKIE, buildCookie("", 0, COOKIE_PATH).toString());
        headers.add(HttpHeaders.SET_COOKIE, buildCookie("", 0, LEGACY_COOKIE_PATH).toString());
        appendHostOnlyClears(headers);
        return headers;
    }

    private void appendHostOnlyClears(HttpHeaders headers) {
        if (domain.isEmpty()) {
            return;
        }
        headers.add(HttpHeaders.SET_COOKIE, buildHostOnlyClear(COOKIE_PATH).toString());
        headers.add(HttpHeaders.SET_COOKIE, buildHostOnlyClear(LEGACY_COOKIE_PATH).toString());
    }

    private ResponseCookie buildCookie(String value, long maxAgeSec, String path) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(path)
                .maxAge(Duration.ofSeconds(maxAgeSec));
        if (!domain.isEmpty()) {
            builder.domain(domain);
        }
        return builder.build();
    }

    private ResponseCookie buildHostOnlyClear(String path) {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path(path)
                .maxAge(Duration.ZERO)
                .build();
    }
}
