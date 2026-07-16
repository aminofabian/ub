package zelisline.ub.tenancy.api.dto;

public record SuppliersAccessSettingsResponse(
        boolean allowSupplierWriteForStockManager,
        boolean allowSupplierWriteForCashier,
        boolean allowLinkProductsForStockManager,
        boolean allowLinkProductsForCashier
) {
    public static SuppliersAccessSettingsResponse defaults() {
        return new SuppliersAccessSettingsResponse(false, false, false, false);
    }
}
