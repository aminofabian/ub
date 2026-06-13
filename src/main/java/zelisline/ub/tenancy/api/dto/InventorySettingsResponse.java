package zelisline.ub.tenancy.api.dto;

public record InventorySettingsResponse(
        StocktakeSettingsResponse stocktake,
        StockLevelsSettingsResponse stockLevels
) {
    public static InventorySettingsResponse defaults() {
        return new InventorySettingsResponse(
                StocktakeSettingsResponse.defaults(),
                StockLevelsSettingsResponse.defaults()
        );
    }
}
