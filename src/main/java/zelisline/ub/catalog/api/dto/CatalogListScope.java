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
     * Sellable catalog lines only: option SKUs and standalone products
     * with {@code sellable = true}. Family/group parents (including empty
     * groups with no children yet) are omitted.
     */
    SKUS_ONLY
}
