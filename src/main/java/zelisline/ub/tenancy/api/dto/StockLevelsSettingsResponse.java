package zelisline.ub.tenancy.api.dto;

public record StockLevelsSettingsResponse(
        boolean allowStockEditForStockManager,
        boolean allowStockEditForGroceryClerk
) {
    public static StockLevelsSettingsResponse defaults() {
        return new StockLevelsSettingsResponse(false, false);
    }
}
