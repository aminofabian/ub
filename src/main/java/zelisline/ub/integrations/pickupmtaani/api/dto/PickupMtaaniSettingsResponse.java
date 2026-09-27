package zelisline.ub.integrations.pickupmtaani.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Merchant-visible Pickup Mtaani option. Credential details (key, Pickup Mtaani
 * business id, account mode, last-verified time) are super-admin only and never
 * appear here (scope §6). {@code status} explains why the option is unavailable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PickupMtaaniSettingsResponse(
        boolean enabled,
        String businessName,
        Long originAgentId,
        String originAgentName,
        String originLocationName,
        String feeMode,
        int markupKes,
        boolean agent,
        boolean doorstep,
        boolean bookOnDispatch,
        String status,
        String statusDetail,
        boolean ready
) {

    public static PickupMtaaniSettingsResponse disconnected() {
        return new PickupMtaaniSettingsResponse(
                false, null,
                null, null, null,
                "pass_through", 0,
                true, true, true,
                "disconnected", null, false);
    }
}
