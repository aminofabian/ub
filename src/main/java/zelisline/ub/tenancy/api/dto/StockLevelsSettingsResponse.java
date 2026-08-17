package zelisline.ub.tenancy.api.dto;

public record StockLevelsSettingsResponse(
        boolean allowStockEditForStockManager,
        /**
         * Grocery counter Edit stock mode for {@code grocery_clerk}. Default on —
         * admin can turn off in business settings.
         */
        boolean allowStockEditForGroceryClerk,
        boolean allowNegativeStock,
        /** Activity page (`/analytics/activity`) for stock managers. Default on. */
        boolean allowActivityForStockManager,
        /** Stock / restock / missing-barcode pages for stock managers. Default on. */
        boolean allowStockPageForStockManager,
        /**
         * Grocery counter Spoils mode for {@code grocery_clerk}. Default on —
         * admin can turn off in business settings.
         */
        boolean allowSpoilsForGroceryClerk
) {
    public static StockLevelsSettingsResponse defaults() {
        return new StockLevelsSettingsResponse(false, true, false, true, true, true);
    }
}
