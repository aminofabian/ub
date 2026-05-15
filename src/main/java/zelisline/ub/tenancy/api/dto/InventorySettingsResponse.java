package zelisline.ub.tenancy.api.dto;

public record InventorySettingsResponse(
        StocktakeSettingsResponse stocktake
) {
    public static InventorySettingsResponse defaults() {
        return new InventorySettingsResponse(StocktakeSettingsResponse.defaults());
    }
}
