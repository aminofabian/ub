package zelisline.ub.tenancy.api.dto;

public record StockLevelsSettingsResponse(
        boolean allowStockEditForStockManager,
        boolean allowStockEditForGroceryClerk,
        boolean allowNegativeStock,
        /** Activity page (`/analytics/activity`) for stock managers. Default on. */
        boolean allowActivityForStockManager,
        /** Stock / restock / missing-barcode pages for stock managers. Default on. */
        boolean allowStockPageForStockManager
) {
    public static StockLevelsSettingsResponse defaults() {
        return new StockLevelsSettingsResponse(false, false, false, true, true);
    }
}
