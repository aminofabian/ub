package zelisline.ub.tenancy.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Picks up every custom domain left PENDING after Connect — once public DNS
 * matches the Coolify targets, attaches the hostname and moves it to VERIFYING.
 * Also retries FAILED domains after a cool-off so a fixed Coolify token or a
 * provider outage heals itself instead of needing a manual "Check connection".
 */
@Component
public class DomainDnsAttachScheduler {

    private static final Logger log = LoggerFactory.getLogger(DomainDnsAttachScheduler.class);

    private final DomainMappingRepository domainMappingRepository;
    private final StorefrontDomainsProperties properties;
    private final StorefrontDomainProvider storefrontDomainProvider;
    private final TenancyService tenancyService;

    public DomainDnsAttachScheduler(
            DomainMappingRepository domainMappingRepository,
            StorefrontDomainsProperties properties,
            StorefrontDomainProvider storefrontDomainProvider,
            TenancyService tenancyService
    ) {
        this.domainMappingRepository = domainMappingRepository;
        this.properties = properties;
        this.storefrontDomainProvider = storefrontDomainProvider;
        this.tenancyService = tenancyService;
    }

    @Scheduled(
            fixedDelayString = "${app.domains.dns-attach-fixed-delay-ms:45000}",
            initialDelayString = "${app.domains.dns-attach-initial-delay-ms:60000}"
    )
    @Transactional
    public void tick() {
        if (!properties.isCoolifyProvider() || !storefrontDomainProvider.configured()) {
            return;
        }
        List<DomainMapping> candidates = new ArrayList<>(
                domainMappingRepository.findByStatusAndDeletedAtIsNull(DomainStatus.PENDING));
        candidates.addAll(retryableFailedDomains());
        if (candidates.isEmpty()) {
            return;
        }
        int verifying = 0;
        int activated = 0;
        int waiting = 0;
        int retried = 0;
        for (DomainMapping domain : candidates) {
            if (domain.getSource() == DomainSource.PLATFORM_SUBDOMAIN) {
                continue;
            }
            if (domain.getSource() != DomainSource.MANUAL_CONNECT
                    && domain.getSource() != DomainSource.HOSTAFRICA_PURCHASE) {
                continue;
            }
            if (domain.getStatus() == DomainStatus.FAILED) {
                retried++;
            }
            try {
                var result = tenancyService.advanceStorefrontDomain(domain);
                domainMappingRepository.save(domain);
                switch (result) {
                    case VERIFYING -> verifying++;
                    case ACTIVATED -> activated++;
                    case WAITING_DNS, PROVIDER_UNAVAILABLE -> waiting++;
                    default -> {
                    }
                }
            } catch (Exception ex) {
                log.warn("Auto-attach failed for {}: {}", domain.getDomain(), ex.getMessage());
            }
        }
        if (verifying > 0 || activated > 0 || retried > 0) {
            log.info(
                    "Domain DNS attach: verifying={}, activated={}, still_waiting={}, retried_failed={}",
                    verifying,
                    activated,
                    waiting,
                    retried
            );
        }
    }

    /**
     * FAILED domains that are safe to retry: provider errors cool off for
     * {@code app.domains.failed-retry-minutes}; certificate timeouts are left
     * alone because they usually mean DNS still points elsewhere.
     */
    private List<DomainMapping> retryableFailedDomains() {
        Instant cutoff = Instant.now().minus(
                Duration.ofMinutes(Math.max(1, properties.getFailedRetryMinutes())));
        return domainMappingRepository.findByStatusAndDeletedAtIsNull(DomainStatus.FAILED).stream()
                .filter(domain -> domain.getUpdatedAt() == null || domain.getUpdatedAt().isBefore(cutoff))
                .filter(domain -> domain.getLastError() == null
                        || !domain.getLastError().startsWith("certificate_timeout_after_"))
                .toList();
    }
}
