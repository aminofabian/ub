package zelisline.ub.tenancy.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.application.PlatformDomainSettingsService;
import zelisline.ub.platform.application.ResolvedDomainIntegrationsConfig;

/**
 * Advances open domain orders (ownership poll → Vercel provision → live).
 * Enabled from Super Admin → Platform → Domains (not env).
 */
@Component
@RequiredArgsConstructor
public class DomainOrderSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(DomainOrderSyncScheduler.class);

    private final DomainPurchaseService domainPurchaseService;
    private final PlatformDomainSettingsService domainSettingsService;

    @Scheduled(fixedDelayString = "${app.integrations.domains.sync-fixed-delay-ms:60000}", initialDelayString = "${app.integrations.domains.sync-initial-delay-ms:20000}")
    public void tick() {
        ResolvedDomainIntegrationsConfig cfg = domainSettingsService.resolve();
        if (!cfg.domainOrderSyncEnabled()) {
            return;
        }
        if (!cfg.hostafricaConfigured() || !cfg.vercelConfigured()) {
            return;
        }
        int n = domainPurchaseService.syncOpenOrders();
        if (n > 0) {
            log.debug("Domain order sync advanced {} open order(s)", n);
        }
    }
}
