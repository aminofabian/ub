package zelisline.ub.platform.security;

import jakarta.servlet.http.HttpServletRequest;

/** Best-effort client IP for rate limits (honours {@code X-Forwarded-For} first hop). */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
