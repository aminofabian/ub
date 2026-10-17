package zelisline.ub.integrations.easytax.api.dto;

public record EasyTaxBranchSettingsResponse(
        String branchId,
        String kraBranchId,
        String deviceSerial,
        /** Both values present, so this branch can report to KRA. */
        boolean configured
) {
}
