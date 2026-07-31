package zelisline.ub.platform.api.dto;

import java.util.Map;

/**
 * Secret fields: {@code null} = leave unchanged; blank string = clear stored value.
 * Other fields: {@code null} = leave unchanged.
 * <p>
 * Palmart STK credentials are sent as individual fields and stored as one encrypted JSON blob.
 * {@code hostafricaRegistrantDefaults} / {@code hostafricaResellerWhois}: null = leave; empty map = clear.
 */
public record UpdatePlatformDomainSettingsRequest(
        String hostafricaApiKey,
        String hostafricaApiBaseUrl,
        String hostafricaCurrency,
        String hostafricaKenyanTlds,
        Boolean hostafricaBillingStubEnabled,
        Map<String, String> hostafricaRegistrantDefaults,
        String hostafricaResellerEmail,
        String hostafricaResellerApiKey,
        String hostafricaResellerApiBaseUrl,
        Map<String, String> hostafricaResellerWhois,
        Boolean clearHostafricaResellerApiKey,
        String palmartStkClientId,
        String palmartStkClientSecret,
        String palmartStkApiKey,
        String palmartStkTillNumber,
        String palmartStkEnvironment,
        Boolean clearPalmartStkCredentials,
        String vercelToken,
        String vercelTeamId,
        String vercelProjectId,
        String vercelApiBaseUrl,
        Boolean domainOrderSyncEnabled,
        Integer domainOrderSyncFixedDelayMs,
        Integer domainOrderSyncInitialDelayMs
) {}
