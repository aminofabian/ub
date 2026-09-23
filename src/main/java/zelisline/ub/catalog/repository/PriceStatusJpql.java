package zelisline.ub.catalog.repository;

/**
 * Shared JPQL for catalog price buckets. A price counts as set only when it is
 * present and greater than zero. Selling price is the shelf price ({@code bundlePrice})
 * or an open selling-price row, matching the existing missing-price filter.
 */
public final class PriceStatusJpql {

    private PriceStatusJpql() {
    }

    public static final String SELLING_SET =
            "((i.bundlePrice is not null and i.bundlePrice > 0)"
                    + " or exists (select 1 from SellingPrice spSet"
                    + " where spSet.itemId = i.id and spSet.businessId = i.businessId"
                    + " and spSet.effectiveTo is null and spSet.price > 0))";

    public static final String SELLING_MISSING =
            "((i.bundlePrice is null or i.bundlePrice <= 0)"
                    + " and not exists (select 1 from SellingPrice spMiss"
                    + " where spMiss.itemId = i.id and spMiss.businessId = i.businessId"
                    + " and spMiss.effectiveTo is null and spMiss.price > 0))";

    public static final String BUYING_SET =
            "(i.buyingPrice is not null and i.buyingPrice > 0)";

    public static final String BUYING_MISSING =
            "(i.buyingPrice is null or i.buyingPrice <= 0)";

    /** Mutually exclusive buckets. {@code ALL} adds no extra constraint. */
    public static final String FILTER =
            " and (:priceStatus = 'ALL'"
                    + " or (:priceStatus = 'MISSING_BUYING' and " + SELLING_SET + " and " + BUYING_MISSING + ")"
                    + " or (:priceStatus = 'MISSING_SELLING' and " + BUYING_SET + " and " + SELLING_MISSING + ")"
                    + " or (:priceStatus = 'BOTH_MISSING' and " + BUYING_MISSING + " and " + SELLING_MISSING + ")"
                    + " or (:priceStatus = 'BOTH_SET' and " + BUYING_SET + " and " + SELLING_SET + ")"
                    + ") ";
}
