package zelisline.ub.tenancy.api.dto;

public record BranchReceiptSettingsResponse(
        String phone,
        String email,
        String website,
        String tillNumber,
        String footerNote,
        /** CUPS queue name on the till Mac (from `lpstat -v`), e.g. Caysn_CN811_UB. */
        String printerCupsName,
        /**
         * When true, cashiers can open a presentable WhatsApp receipt for the
         * customer after a sale.
         */
        boolean whatsappReceiptEnabled
) {
    public static BranchReceiptSettingsResponse empty() {
        return new BranchReceiptSettingsResponse(null, null, null, null, null, null, false);
    }
}
