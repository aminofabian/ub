package zelisline.ub.catalog.api.dto;

/**
 * Catalog list bucket for buying vs selling price completeness.
 * Selling is set when the shelf price or an open selling-price row is above zero.
 * Buying is set when {@code buyingPrice} is above zero.
 */
public enum PriceStatusFilter {
    ALL,
    /** Selling price is set and buying price is empty. */
    MISSING_BUYING,
    /** Buying price is set and selling price is empty. */
    MISSING_SELLING,
    BOTH_MISSING,
    BOTH_SET
}
