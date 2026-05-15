package zelisline.ub.tenancy.api.dto;

public record StocktakeSettingsResponse(
        boolean showSystemStockToStockManager
) {
    public static StocktakeSettingsResponse defaults() {
        return new StocktakeSettingsResponse(false);
    }
}
