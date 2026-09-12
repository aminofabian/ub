package zelisline.ub.tenancy.api.dto;

public record ReceiveStockSettingsResponse(
        boolean allowReceiveForCashier,
        boolean allowReceiveForStockManager,
        /**
         * Path B receive (“stock in”) on the grocery counter for {@code grocery_clerk}.
         * Default on — admin can turn off in business settings.
         */
        boolean allowReceiveForGroceryClerk,
        /**
         * When true: Confirm order uses Mark arrived → Unpack into stock.
         * Default off (one-step unpack). Can still override per GRN with {@code overrideArrival}.
         */
        boolean twoStepDelivery
) {
    /** Defaults on for receive roles; two-step delivery off so classic one-step stays default. */
    public static ReceiveStockSettingsResponse defaults() {
        return new ReceiveStockSettingsResponse(true, true, true, false);
    }
}
