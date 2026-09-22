package zelisline.ub.catalog.api.dto;

/**
 * Server-side catalog list ordering. Prefer this over raw Spring {@code sort=}
 * when the order depends on derived pricing (profit / margin).
 */
public enum CatalogListSort {
    /** Product name A→Z (default). */
    NAME_ASC,
    NAME_DESC,
    /** Sell price cheapest → expensive. */
    SELL_ASC,
    /** Sell price expensive → cheapest. */
    SELL_DESC,
    /** Buy / cost price ascending. */
    BUY_ASC,
    BUY_DESC,
    /** Absolute margin (sell − buy), most profitable first. */
    PROFIT_DESC,
    PROFIT_ASC,
    /** Margin percent ((sell − buy) / buy), highest first. */
    MARGIN_DESC,
    MARGIN_ASC
}
