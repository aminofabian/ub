package zelisline.ub.tenancy.application;

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
 * Merchants should not need to babysit Check connection for each shop.
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
        List<DomainMapping> pending = domainMappingRepository.findByStatusAndDeletedAtIsNull(DomainStatus.PENDING);
        if (pending.isEmpty()) {
            return;
        }
        int verifying = 0;
        int activated = 0;
        int waiting = 0;
        for (DomainMapping domain : pending) {
            if (domain.getSource() == DomainSource.PLATFORM_SUBDOMAIN) {
                continue;
            }
            if (domain.getSource() != DomainSource.MANUAL_CONNECT
                    && domain.getSource() != DomainSource.HOSTAFRICA_PURCHASE) {
                continue;
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
        if (verifying > 0 || activated > 0) {
            log.info(
                    "Domain DNS attach: verifying={}, activated={}, still_waiting={}",
                    verifying,
                    activated,
                    waiting
            );
        }
    }
}
