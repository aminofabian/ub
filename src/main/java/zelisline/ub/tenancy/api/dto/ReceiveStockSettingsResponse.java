package zelisline.ub.tenancy.api.dto;

public record ReceiveStockSettingsResponse(
        boolean allowReceiveForCashier,
        boolean allowReceiveForStockManager
) {
    /** Defaults on so existing shops keep receive-stock until an admin turns it off. */
    public static ReceiveStockSettingsResponse defaults() {
        return new ReceiveStockSettingsResponse(true, true);
    }
}
