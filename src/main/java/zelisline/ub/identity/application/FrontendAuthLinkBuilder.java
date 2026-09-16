package zelisline.ub.identity.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Builds tenant-aware frontend auth links (verify-email, reset-password) with an
 * optional {@code host} query hint so the SPA can resolve {@code X-Tenant-Id}
 * even when the link is opened on apex or bare localhost.
 */
@Component
public class FrontendAuthLinkBuilder {

    private final BusinessRepository businessRepository;

    @Value("${app.public.email-verification-url-prefix:http://localhost:3000/verify-email?token=}")
    private String emailVerificationUrlPrefix;

    @Value("${app.public.password-reset-url-prefix:http://localhost:3000/reset-password?token=}")
    private String passwordResetUrlPrefix;

    @Value("${app.tenancy.slug-domain-suffix:}")
    private String slugDomainSuffix;

    public FrontendAuthLinkBuilder(BusinessRepository businessRepository) {
        this.businessRepository = businessRepository;
    }

    public String verificationLink(HttpServletRequest http, String businessId, String rawToken) {
        return buildLink(http, "verify-email", emailVerificationUrlPrefix, businessId, rawToken);
    }

    public String passwordResetLink(HttpServletRequest http, String businessId, String rawToken) {
        return buildLink(http, "reset-password", passwordResetUrlPrefix, businessId, rawToken);
    }

    private String buildLink(
            HttpServletRequest http,
            String pagePath,
            String staticUrlPrefix,
            String businessId,
            String rawToken
    ) {
        String hostHint = resolveFrontendHost(http);
        if (hostHint == null) {
            hostHint = resolveHostFromBusinessSlug(businessId).orElse(null);
        }
        String prefix = buildUrlPrefix(http, pagePath, staticUrlPrefix);
        StringBuilder link = new StringBuilder(prefix).append(rawToken);
        if (hostHint != null && !hostHint.isBlank()) {
            link.append("&host=")
                    .append(URLEncoder.encode(hostHint.trim(), StandardCharsets.UTF_8));
        }
        return link.toString();
    }

    String resolveFrontendHost(HttpServletRequest http) {
        String frontendHost = http.getHeader("X-Tenant-Host");
        if (frontendHost == null || frontendHost.isBlank()) {
            String serverName = http.getServerName();
            if (serverName != null && !serverName.isBlank()
                    && !"localhost".equalsIgnoreCase(serverName)
                    && !"127.0.0.1".equals(serverName)
                    && !"::1".equals(serverName)) {
                frontendHost = serverName;
            }
        }
        return (frontendHost == null || frontendHost.isBlank()) ? null : frontendHost.trim();
    }

    private Optional<String> resolveHostFromBusinessSlug(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return Optional.empty();
        }
        String suffix = slugDomainSuffix == null ? "" : slugDomainSuffix.trim().toLowerCase(Locale.ROOT);
        if (suffix.isEmpty()) {
            return Optional.empty();
        }
        return businessRepository.findByIdAndDeletedAtIsNull(businessId.trim())
                .map(Business::getSlug)
                .map(slug -> {
                    String s = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
                    if (s.isEmpty()) {
                        return null;
                    }
                    return s + "." + suffix;
                })
                .filter(h -> h != null && !h.isBlank());
    }

    private String buildUrlPrefix(HttpServletRequest http, String pagePath, String staticUrlPrefix) {
        String frontendHost = resolveFrontendHost(http);
        if (frontendHost == null) {
            return staticUrlPrefix;
        }
        String hostname = hostnameOnly(frontendHost);
        if (hostname.isEmpty()) {
            return staticUrlPrefix;
        }

        // Scheme and port must describe the SAME origin as `hostname`.
        //
        // The host is the browser's (X-Tenant-Host), but the old code took the
        // scheme and port from the API's own connection. Behind the Next.js BFF
        // that connection is plain HTTP on the app's listening port, so every
        // link came out as `http://shop.example:5050/verify-email?...` — a URL
        // no user can open. Prefer an explicit forwarded value, then fall back
        // by host kind: local dev keeps the connection's values, anything else
        // is treated as a public HTTPS origin.
        boolean local = isLocalHost(hostname);
        String forwardedProto = headerFirstValue(http, "X-Forwarded-Proto");
        String scheme = forwardedProto != null
                ? forwardedProto
                : (local ? http.getScheme() : "https");

        Integer port = explicitPort(frontendHost);
        if (port == null) {
            port = parsePort(headerFirstValue(http, "X-Forwarded-Port"));
        }
        if (port == null && local) {
            port = 3000;
        }

        boolean defaultPort = port != null
                && ((port == 80 && "http".equals(scheme))
                        || (port == 443 && "https".equals(scheme)));

        StringBuilder prefix = new StringBuilder(scheme)
                .append("://")
                .append(hostname);
        if (port != null && !defaultPort) {
            prefix.append(":").append(port);
        }
        prefix.append("/").append(pagePath).append("?token=");
        return prefix.toString();
    }

    /** First value of a possibly comma-separated forwarded header, or null. */
    private static String headerFirstValue(HttpServletRequest http, String name) {
        String raw = http.getHeader(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String first = raw.split(",")[0].trim();
        return first.isEmpty() ? null : first;
    }

    /** Lowercased host without its port (IPv6 literals keep their brackets). */
    private static String hostnameOnly(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) {
            return "";
        }
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            return close > 0 ? h.substring(0, close + 1) : h;
        }
        int colon = h.indexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }

    /** Port explicitly present in a {@code host:port} value, else null. */
    private static Integer explicitPort(String host) {
        String h = host == null ? "" : host.trim();
        if (h.isEmpty() || h.startsWith("[")) {
            return null;
        }
        int colon = h.lastIndexOf(':');
        return colon < 0 ? null : parsePort(h.substring(colon + 1));
    }

    private static Integer parsePort(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            return (port >= 1 && port <= 65535) ? port : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isLocalHost(String hostname) {
        String h = hostname == null ? "" : hostname.toLowerCase(Locale.ROOT);
        return "localhost".equals(h)
                || "127.0.0.1".equals(h)
                || "::1".equals(h)
                || "[::1]".equals(h)
                || h.endsWith(".localhost");
    }

    /** Shop origin for a tenant (slug host). Used by platform email campaigns. */
    public String tenantOrigin(String businessId) {
        String host = resolveHostFromBusinessSlug(businessId).orElse(null);
        if (host == null || host.isBlank()) {
            try {
                java.net.URI prefix = java.net.URI.create(emailVerificationUrlPrefix);
                String scheme = prefix.getScheme() == null ? "https" : prefix.getScheme();
                String h = prefix.getHost();
                if (h == null || h.isBlank()) {
                    return "http://localhost:3000";
                }
                int port = prefix.getPort();
                return port > 0 ? scheme + "://" + h + ":" + port : scheme + "://" + h;
            } catch (RuntimeException ex) {
                return "http://localhost:3000";
            }
        }
        boolean local = host.contains("localhost") || "127.0.0.1".equals(host);
        String scheme = local ? "http" : "https";
        if (local && !host.contains(":")) {
            return scheme + "://" + host + ":3000";
        }
        return scheme + "://" + host;
    }

    public String verificationLinkForBusiness(String businessId, String rawToken) {
        String hostHint = resolveHostFromBusinessSlug(businessId).orElse(null);
        String origin = tenantOrigin(businessId);
        StringBuilder link = new StringBuilder(origin)
                .append("/verify-email?token=")
                .append(rawToken);
        if (hostHint != null && !hostHint.isBlank()) {
            link.append("&host=")
                    .append(URLEncoder.encode(hostHint.trim(), StandardCharsets.UTF_8));
        }
        return link.toString();
    }
}
