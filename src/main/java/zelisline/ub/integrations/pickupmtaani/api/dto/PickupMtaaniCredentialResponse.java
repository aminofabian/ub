package zelisline.ub.integrations.pickupmtaani.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Super-admin view of the tenant's Pickup Mtaani credential. The key itself is
 * never returned — only whether one is stored.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PickupMtaaniCredentialResponse(
        boolean hasApiKey,
        Long businessId,
        String businessName,
        String accountMode,
        String lastVerifiedAt,
        String status,
        String statusDetail
) {

    public static PickupMtaaniCredentialResponse disconnected() {
        return new PickupMtaaniCredentialResponse(false, null, null, null, null, "disconnected", null);
    }
}
