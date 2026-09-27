package zelisline.ub.integrations.pickupmtaani.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Public, secret-free Pickup Mtaani capability for storefront checkout. Absent
 * from the response entirely when the shop does not offer the option (scope §6).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PickupMtaaniPublicConfig(
        boolean enabled,
        boolean agent,
        boolean doorstep,
        String originLabel
) {
}
