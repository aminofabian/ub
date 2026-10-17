package zelisline.ub.integrations.easytax.api.dto;

import jakarta.validation.constraints.Size;

/** Per-branch KRA identity (entered by the tenant for each branch). */
public record EasyTaxBranchSettingsRequest(
        @Size(max = 32) String kraBranchId,
        @Size(max = 64) String deviceSerial
) {
}
