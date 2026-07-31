package zelisline.ub.platform.api.dto;

import java.time.Instant;
import java.util.Map;

/** Secrets never returned — use {@code has*} flags. */
public record PlatformDomainSettingsResponse(
        boolean hasHostafricaApiKey,
        String hostafricaApiBaseUrl,
        String hostafricaCurrency,
        String hostafricaKenyanTlds,
        boolean hostafricaBillingStubEnabled,
        Map<String, String> hostafricaRegistrantDefaults,
        boolean hasHostafricaResellerApiKey,
        String hostafricaResellerEmail,
        String hostafricaResellerApiBaseUrl,
        boolean hostafricaResellerConfigured,
        Map<String, String> hostafricaResellerWhois,
        boolean hasPalmartStkCredentials,
        String palmartStkTillNumber,
        boolean hasVercelToken,
        String vercelTeamId,
        String vercelProjectId,
        String vercelApiBaseUrl,
        boolean domainOrderSyncEnabled,
        int domainOrderSyncFixedDelayMs,
        int domainOrderSyncInitialDelayMs,
        boolean envHostafricaConfigured,
        boolean envVercelConfigured,
        boolean secretsReadable,
        String secretsError,
        boolean encryptionEphemeral,
        Instant updatedAt
) {}
