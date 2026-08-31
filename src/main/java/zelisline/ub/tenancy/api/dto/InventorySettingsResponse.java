package zelisline.ub.tenancy.api.dto;

public record InventorySettingsResponse(
        StocktakeSettingsResponse stocktake,
        StockLevelsSettingsResponse stockLevels,
        SuppliersAccessSettingsResponse suppliers,
        ReceiveStockSettingsResponse receiveStock,
        CreditTabsSettingsResponse creditTabs,
        CheckoutSettingsResponse checkout,
        CatalogSettingsResponse catalog
) {
    public static InventorySettingsResponse defaults() {
        return new InventorySettingsResponse(
                StocktakeSettingsResponse.defaults(),
                StockLevelsSettingsResponse.defaults(),
                SuppliersAccessSettingsResponse.defaults(),
                ReceiveStockSettingsResponse.defaults(),
                CreditTabsSettingsResponse.defaults(),
                CheckoutSettingsResponse.defaults(),
                CatalogSettingsResponse.defaults()
        );
    }
}
