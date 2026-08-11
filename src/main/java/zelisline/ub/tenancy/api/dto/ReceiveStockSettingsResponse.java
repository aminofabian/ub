package zelisline.ub.tenancy.api.dto;

public record ReceiveStockSettingsResponse(
        boolean allowReceiveForCashier,
        boolean allowReceiveForStockManager,
        /**
         * Path B receive (“stock in”) on the grocery counter for {@code grocery_clerk}.
         * Default on — admin can turn off in business settings.
         */
        boolean allowReceiveForGroceryClerk
) {
    /** Defaults on so existing shops keep receive-stock until an admin turns it off. */
    public static ReceiveStockSettingsResponse defaults() {
        return new ReceiveStockSettingsResponse(true, true, true);
    }
}
