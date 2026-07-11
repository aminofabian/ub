package zelisline.ub.tenancy.api.dto;

public record BranchReceiptSettingsResponse(
        String phone,
        String email,
        String website,
        String tillNumber,
        String footerNote,
        /** CUPS queue name on the till Mac (from `lpstat -v`), e.g. Caysn_CN811_UB. */
        String printerCupsName
) {
    public static BranchReceiptSettingsResponse empty() {
        return new BranchReceiptSettingsResponse(null, null, null, null, null, null);
    }
}
