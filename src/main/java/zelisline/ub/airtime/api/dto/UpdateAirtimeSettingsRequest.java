package zelisline.ub.airtime.api.dto;

import java.math.BigDecimal;

/** Tenant airtime toggles. Null fields are left unchanged. */
public record UpdateAirtimeSettingsRequest(
        Boolean enabled,
        Boolean posEnabled,
        Boolean storefrontEnabled,
        BigDecimal maxSingleAmount
) {
}
