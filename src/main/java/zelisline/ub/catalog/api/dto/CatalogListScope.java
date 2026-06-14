package zelisline.ub.catalog.api.dto;

/**
 * Controls which catalog rows are returned when listing items.
 * Server-side filtering avoids loading all SKUs only to hide variants client-side.
 */
public enum CatalogListScope {
    ALL,
    /** Group-label parents only (root items that have variant children). */
    PARENTS_ONLY,
    VARIANTS_ONLY,
    /**
     * Sellable catalog lines only: option SKUs and standalone products.
     * Group-only parents (they have variant children) are omitted.
     */
    SKUS_ONLY
}
