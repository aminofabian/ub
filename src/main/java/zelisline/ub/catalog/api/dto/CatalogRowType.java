package zelisline.ub.catalog.api.dto;

/**
 * Structural row bucket for catalog list filtering and counts.
 * <ul>
 *   <li>{@link #PARENT} — root item that has one or more variant children</li>
 *   <li>{@link #VARIANT} — item with a parent ({@code variantOfItemId} set)</li>
 *   <li>{@link #STANDALONE} — root item with no variant children</li>
 * </ul>
 */
public enum CatalogRowType {
    PARENT,
    VARIANT,
    STANDALONE
}
