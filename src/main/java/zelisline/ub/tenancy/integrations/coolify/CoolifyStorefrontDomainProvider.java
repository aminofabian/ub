package zelisline.ub.tenancy.integrations.coolify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import zelisline.ub.tenancy.integrations.storefront.DnsTargetChecker;
import zelisline.ub.tenancy.integrations.storefront.StorefrontDnsInstructions;
import zelisline.ub.tenancy.integrations.storefront.StorefrontDomainProvider;
import zelisline.ub.tenancy.integrations.storefront.StorefrontDomainsProperties;

/**
 * Coolify-backed storefront domains: DNS instructions first, attach only after
 * public DNS matches (protects Let's Encrypt rate limits), then await cert.
 */
@Component
@ConditionalOnProperty(name = "app.domains.provider", havingValue = "coolify")
public class CoolifyStorefrontDomainProvider implements StorefrontDomainProvider {

    private final StorefrontDomainsProperties properties;
    private final DnsTargetChecker dnsTargetChecker;
    private final CoolifyApiClient coolifyApiClient;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public CoolifyStorefrontDomainProvider(
            StorefrontDomainsProperties properties,
            DnsTargetChecker dnsTargetChecker,
            CoolifyApiClient coolifyApiClient
    ) {
        this.properties = properties;
        this.dnsTargetChecker = dnsTargetChecker;
        this.coolifyApiClient = coolifyApiClient;
    }

    @Override
    public String id() {
        return "coolify";
    }

    @Override
    public boolean configured() {
        return coolifyApiClient.configured();
    }

    @Override
    public Map<String, Object> recommendedDnsInstructions(String hostname) {
        return StorefrontDnsInstructions.forHostname(
                hostname,
                properties.resolvedATarget(),
                properties.resolvedCnameTarget(),
                id());
    }

    @Override
    public Result connect(String hostname) {
        // No Coolify call yet — Traefik ACME must not see the host before DNS points here.
        return Result.notReady(recommendedDnsInstructions(hostname), null);
    }

    @Override
    public Result check(String hostname) {
        Map<String, Object> dns = recommendedDnsInstructions(hostname);
        var dnsCheck = dnsTargetChecker.check(hostname);
        if (!dnsCheck.matched()) {
            Map<String, Object> withHint = new LinkedHashMap<>(dns);
            withHint.put("checkMessage", dnsCheck.message());
            return Result.notReady(withHint, dnsCheck.message());
        }
        if (!configured()) {
            return Result.failed("coolify_not_configured", null, dns);
        }
        var mutation = coolifyApiClient.ensureHttpsHosts(hostname);
        if (!mutation.ok()) {
            return Result.failed(mutation.error(), mutation.httpStatus(), dns);
        }
        if (httpsReady(hostname)) {
            return Result.verified(dns);
        }
        if (dnsCheck.message() != null && !dnsCheck.message().isBlank()) {
            Map<String, Object> withHint = new LinkedHashMap<>(dns);
            withHint.put("checkMessage", dnsCheck.message());
            return Result.awaitingCertificate(withHint);
        }
        return Result.awaitingCertificate(dns);
    }

    @Override
    public void detach(String hostname) {
        if (!configured()) {
            return;
        }
        coolifyApiClient.removeHttpsHosts(hostname);
    }

    private boolean httpsReady(String hostname) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + hostname + "/api/client-version"))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 500;
        } catch (Exception ex) {
            return false;
        }
    }
}
