package zelisline.ub.catalog.api.dto;

public enum BulkPriceMode {
    /** Replace the side with a fixed amount. */
    SET_AMOUNT,
    /** Multiply the current price by {@code 1 + percent/100}. Requires a current price. */
    INCREASE_PERCENT,
    /** Multiply the current price by {@code 1 - percent/100}. Requires a current price. */
    DECREASE_PERCENT,
    /**
     * Buying: percent of the selling price (80 → buying = selling × 0.80).
     * Selling: markup on the buying price (25 → selling = buying × 1.25).
     */
    PERCENT_OF_COUNTERPART
}
