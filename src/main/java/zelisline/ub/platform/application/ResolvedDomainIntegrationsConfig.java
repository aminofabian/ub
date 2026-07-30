package zelisline.ub.platform.application;

/**
 * Runtime HostAfrica + Vercel config resolved from Super Admin DB (preferred) with env fallback.
 */
public record ResolvedDomainIntegrationsConfig(
        String hostafricaApiKey,
        String hostafricaApiBaseUrl,
        String hostafricaCurrency,
        String hostafricaKenyanTlds,
        boolean hostafricaBillingStubEnabled,
        String vercelToken,
        String vercelTeamId,
        String vercelProjectId,
        String vercelApiBaseUrl,
        boolean domainOrderSyncEnabled,
        int domainOrderSyncFixedDelayMs,
        int domainOrderSyncInitialDelayMs
) {
    public boolean hostafricaConfigured() {
        return hostafricaApiKey != null && !hostafricaApiKey.isBlank();
    }

    public boolean vercelConfigured() {
        return vercelToken != null && !vercelToken.isBlank()
                && vercelProjectId != null && !vercelProjectId.isBlank();
    }
}
