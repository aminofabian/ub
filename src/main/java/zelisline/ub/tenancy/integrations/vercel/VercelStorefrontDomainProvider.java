package zelisline.ub.tenancy.integrations.vercel;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import zelisline.ub.tenancy.integrations.storefront.StorefrontDnsInstructions;
import zelisline.ub.tenancy.integrations.storefront.StorefrontDomainProvider;
import zelisline.ub.tenancy.integrations.storefront.StorefrontDomainsProperties;

/**
 * Adapts {@link VercelProjectDomainClient} to {@link StorefrontDomainProvider}.
 * Active when {@code app.domains.provider=vercel} (the pre-cutover default).
 */
@Component
@ConditionalOnProperty(name = "app.domains.provider", havingValue = "vercel", matchIfMissing = true)
public class VercelStorefrontDomainProvider implements StorefrontDomainProvider {

    private final VercelProjectDomainClient vercelProjectDomainClient;
    private final StorefrontDomainsProperties properties;

    public VercelStorefrontDomainProvider(
            VercelProjectDomainClient vercelProjectDomainClient,
            StorefrontDomainsProperties properties
    ) {
        this.vercelProjectDomainClient = vercelProjectDomainClient;
        this.properties = properties;
    }

    @Override
    public String id() {
        return "vercel";
    }

    @Override
    public boolean configured() {
        return vercelProjectDomainClient.configured();
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
        if (!configured()) {
            return Result.skipped("vercel_not_configured");
        }
        var r = vercelProjectDomainClient.addDomain(hostname);
        return map(r);
    }

    @Override
    public Result check(String hostname) {
        if (!configured()) {
            return Result.skipped("vercel_not_configured");
        }
        var r = vercelProjectDomainClient.verifyDomain(hostname);
        return map(r);
    }

    @Override
    public void detach(String hostname) {
        if (!configured()) {
            return;
        }
        vercelProjectDomainClient.removeDomain(hostname);
    }

    private Result map(VercelProjectDomainClient.ProjectDomainResult r) {
        Map<String, Object> dns = r.dnsInstructions() == null || r.dnsInstructions().isEmpty()
                ? recommendedDnsInstructions(r.name() == null ? "" : r.name())
                : r.dnsInstructions();
        if (r.skipped()) {
            return Result.skipped(r.error());
        }
        if (!r.ok()) {
            return Result.failed(r.error(), r.httpStatus(), dns);
        }
        if (r.verified()) {
            return Result.verified(dns);
        }
        return Result.notReady(dns, null);
    }
}
