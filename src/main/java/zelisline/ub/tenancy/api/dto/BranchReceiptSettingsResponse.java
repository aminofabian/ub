package zelisline.ub.tenancy.api.dto;

public record BranchReceiptSettingsResponse(
        String phone,
        String email,
        String website,
        String tillNumber,
        String footerNote
) {
    public static BranchReceiptSettingsResponse empty() {
        return new BranchReceiptSettingsResponse(null, null, null, null, null);
    }
}
