package zelisline.ub.tenancy.application;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.domain.DomainSource;
import zelisline.ub.tenancy.domain.DomainStatus;
import zelisline.ub.tenancy.integrations.storefront.StorefrontDomainProvider;
import zelisline.ub.tenancy.integrations.storefront.StorefrontDomainsProperties;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Advances Coolify-attached domains from VERIFYING → ACTIVE once HTTPS serves
 * a valid cert. Marks FAILED after {@code app.domains.verify-timeout-minutes}.
 */
@Component
public class DomainCertProbeScheduler {

    private static final Logger log = LoggerFactory.getLogger(DomainCertProbeScheduler.class);

    private final DomainMappingRepository domainMappingRepository;
    private final StorefrontDomainsProperties properties;
    private final StorefrontDomainProvider storefrontDomainProvider;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public DomainCertProbeScheduler(
            DomainMappingRepository domainMappingRepository,
            StorefrontDomainsProperties properties,
            StorefrontDomainProvider storefrontDomainProvider
    ) {
        this.domainMappingRepository = domainMappingRepository;
        this.properties = properties;
        this.storefrontDomainProvider = storefrontDomainProvider;
    }

    @Scheduled(fixedDelayString = "${app.domains.cert-probe-fixed-delay-ms:30000}", initialDelayString = "${app.domains.cert-probe-initial-delay-ms:45000}")
    @Transactional
    public void tick() {
        if (!properties.isCoolifyProvider() || !storefrontDomainProvider.configured()) {
            return;
        }
        List<DomainMapping> verifying = domainMappingRepository.findByStatusAndDeletedAtIsNull(DomainStatus.VERIFYING);
        if (verifying.isEmpty()) {
            return;
        }
        int timeoutMinutes = Math.max(5, properties.getVerifyTimeoutMinutes());
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(timeoutMinutes));
        int activated = 0;
        int failed = 0;
        for (DomainMapping domain : verifying) {
            if (domain.getSource() == DomainSource.PLATFORM_SUBDOMAIN) {
                continue;
            }
            if (httpsReady(domain.getDomain())) {
                domain.setActive(true);
                domain.setStatus(DomainStatus.ACTIVE);
                domain.setVerifiedAt(Instant.now());
                domain.setLastError(null);
                domainMappingRepository.save(domain);
                activated++;
                continue;
            }
            Instant started = domain.getUpdatedAt() != null ? domain.getUpdatedAt() : domain.getCreatedAt();
            if (started != null && started.isBefore(cutoff)) {
                domain.setStatus(DomainStatus.FAILED);
                domain.setLastError("certificate_timeout_after_" + timeoutMinutes + "m");
                domainMappingRepository.save(domain);
                failed++;
            }
        }
        if (activated > 0 || failed > 0) {
            log.info("Domain cert probe: activated={}, failed={}", activated, failed);
        }
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
            log.debug("HTTPS probe failed for {}: {}", hostname, ex.getMessage());
            return false;
        }
    }
}
