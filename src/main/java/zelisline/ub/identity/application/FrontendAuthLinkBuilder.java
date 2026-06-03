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

        String scheme = http.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.isBlank()) {
            scheme = http.getScheme();
        }

        int port = http.getServerPort();
        if (frontendHost.endsWith(".localhost") || "localhost".equalsIgnoreCase(frontendHost)) {
            port = 3000;
        }
        boolean defaultPort = (port == 80 && "http".equals(scheme))
                || (port == 443 && "https".equals(scheme));

        StringBuilder prefix = new StringBuilder(scheme)
                .append("://")
                .append(frontendHost);
        if (!defaultPort) {
            prefix.append(":").append(port);
        }
        prefix.append("/").append(pagePath).append("?token=");
        return prefix.toString();
    }
}
