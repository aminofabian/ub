package zelisline.ub.desktop.license;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Blocks mutating API calls when the desktop license is expired or invalid
 * (see {@code DESKTOP_INSTALLATION.md} §10).
 *
 * <p>GET/HEAD/OPTIONS always pass. Auth and license renewal stay writable so
 * the owner can sign in and paste a new key.
 */
@Component
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopLicenseReadOnlyFilter extends OncePerRequestFilter {

    static final String PROBLEM_TYPE = "urn:problem:license-read-only";

    private static final List<String> WRITE_WHITELIST_PREFIXES = List.of(
        "/api/v1/auth/",
        "/api/v1/license"
    );

    private final DesktopLicenseGuard licenseGuard;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String method = request.getMethod();
        if (
            HttpMethod.GET.matches(method) ||
            HttpMethod.HEAD.matches(method) ||
            HttpMethod.OPTIONS.matches(method)
        ) {
            return true;
        }
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/")) {
            return true;
        }
        return isWriteWhitelisted(path);
    }

    @Override
    protected void doFilterInternal(
        @NonNull HttpServletRequest request,
        @NonNull HttpServletResponse response,
        @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        if (!licenseGuard.isReadOnly()) {
            filterChain.doFilter(request, response);
            return;
        }

        LicenseStatus status = licenseGuard.currentStatus();
        writeReadOnly(response, status.message());
    }

    static boolean isWriteWhitelisted(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        for (String prefix : WRITE_WHITELIST_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        // Protective backup while expired; restore remains blocked.
        if (normalized.startsWith("/api/v1/desktop/backups/now")) {
            return true;
        }
        return normalized.startsWith("/api/v1/desktop/devices/");
    }

    private void writeReadOnly(HttpServletResponse response, String detail)
        throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", PROBLEM_TYPE);
        body.put("title", "License read-only");
        body.put("status", HttpStatus.LOCKED.value());
        body.put(
            "detail",
            detail != null && !detail.isBlank()
                ? detail
                : "Your license has expired. Renew to make changes."
        );

        response.setStatus(HttpStatus.LOCKED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
