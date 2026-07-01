package zelisline.ub.tenancy.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Mobile distribution settings persisted under {@code settings.mobile} on a business.
 */
public record MobileSettingsResponse(
        boolean provisioned,
        Instant provisionedAt,
        String scheme,
        Map<String, MobileAppSettingsDto> apps,
        MobileStoreLinksDto storeLinks
) {
    public static MobileSettingsResponse notProvisioned() {
        return new MobileSettingsResponse(false, null, null, Map.of(), MobileStoreLinksDto.empty());
    }
}
