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
        boolean allowSpoilsForGroceryClerk,
        /**
         * Grocery counter: set minimum / reorder level in Edit stock. Default on.
         */
        boolean allowMinStockForGroceryClerk,
        /**
         * Grocery counter supplier Order drawer (Path A place + WhatsApp). Default on.
         */
        boolean allowOrderPadForGroceryClerk,
        /**
         * Grocery counter Confirm (Path A receive) drawer. Default on.
         */
        boolean allowOrderConfirmForGroceryClerk
) {
    public static StockLevelsSettingsResponse defaults() {
        return new StockLevelsSettingsResponse(
                false, true, false, true, true, true, true, true, true);
    }
}
