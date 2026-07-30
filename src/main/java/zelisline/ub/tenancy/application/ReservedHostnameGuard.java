package zelisline.ub.tenancy.application;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Blocks tenants from claiming platform apex hosts, the shared slug parent zone,
 * or reserved operational hostnames.
 */
@Component
public class ReservedHostnameGuard {

    private static final Set<String> HARD_RESERVED = Set.of(
            "localhost",
            "127.0.0.1",
            "::1",
            "www",
            "api",
            "admin",
            "app",
            "mail",
            "ftp",
            "cdn",
            "static",
            "assets",
            "status",
            "docs",
            "help",
            "support",
            "billing",
            "pay",
            "webhook",
            "webhooks",
            "super-admin",
            "superadmin",
            "platform",
            "null",
            "undefined"
    );

    private final Set<String> platformHosts;
    private final String slugDomainSuffix;

    public ReservedHostnameGuard(
            @Value("${app.tenancy.platform-hosts:}") Collection<String> platformHosts,
            @Value("${app.tenancy.slug-domain-suffix:}") String slugDomainSuffix
    ) {
        this.platformHosts = normalizeSet(platformHosts);
        this.slugDomainSuffix = blankToNull(slugDomainSuffix);
    }

    public void assertClaimable(String hostname) {
        String host = normalize(hostname);
        if (host == null || host.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Domain is required");
        }
        if (host.contains("..") || host.startsWith(".") || host.endsWith(".")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid domain hostname");
        }
        if (host.contains("/") || host.contains(":") || host.contains(" ")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid domain hostname");
        }

        if (platformHosts.contains(host)) {
            throw reserved(host);
        }

        if (slugDomainSuffix != null) {
            if (host.equals(slugDomainSuffix) || host.equals("www." + slugDomainSuffix)) {
                throw reserved(host);
            }
            // Tenants must not claim the shared parent zone itself or invent
            // sibling platform hosts under it (only {slug}.{suffix} is issued by us).
            if (host.endsWith("." + slugDomainSuffix)) {
                String label = host.substring(0, host.length() - slugDomainSuffix.length() - 1);
                if (label.isBlank() || label.contains(".") || HARD_RESERVED.contains(label)) {
                    throw reserved(host);
                }
                // Platform subdomains are provisioned by onboard — not via manual add.
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Platform subdomains are assigned automatically; add a custom domain instead"
                );
            }
        }

        // Bare reserved labels (rare) and known apex TLDs we operate.
        if (HARD_RESERVED.contains(host)) {
            throw reserved(host);
        }
        for (String reserved : HARD_RESERVED) {
            if (host.equals(reserved + ".ke")
                    || host.equals(reserved + ".co.ke")
                    || host.equals("www." + reserved + ".ke")
                    || host.equals("www." + reserved + ".co.ke")) {
                throw reserved(host);
            }
        }
    }

    private static ResponseStatusException reserved(String host) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Hostname is reserved: " + host
        );
    }

    private static Set<String> normalizeSet(Collection<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String h : raw) {
            String n = normalize(h);
            if (n != null && !n.isBlank()) {
                out.add(n);
            }
        }
        return out;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.endsWith(".")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim().toLowerCase(Locale.ROOT);
        return t.isBlank() ? null : t;
    }
}
