package zelisline.ub.tenancy.api.dto;

public record StockLevelsSettingsResponse(
        boolean allowStockEditForStockManager,
        boolean allowStockEditForGroceryClerk,
        boolean allowNegativeStock
) {
    public static StockLevelsSettingsResponse defaults() {
        return new StockLevelsSettingsResponse(false, false, false);
    }
}
