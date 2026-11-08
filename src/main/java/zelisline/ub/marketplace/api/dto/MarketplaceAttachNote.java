package zelisline.ub.marketplace.api.dto;

/**
 * One product an attach did not add as a brand-new item: it was already in the shop and was linked
 * instead of added again, it was already linked to the supplier, or it could not be added.
 *
 * <p>{@code name} and {@code barcode} are the supplier's shelf product (either may be null), and
 * {@code outcome} is one of {@link #EXISTING_LINKED}, {@link #ALREADY_LINKED} or {@link #SKIPPED}.
 */
public record MarketplaceAttachNote(
        String name,
        String barcode,
        String outcome,
        String reason
) {

    /** The shop already had this product (same barcode or name): no duplicate, linked to the supplier. */
    public static final String EXISTING_LINKED = "existing_linked";

    /** The product was already linked to this supplier. */
    public static final String ALREADY_LINKED = "already_linked";

    /** The product was not added; {@code reason} says why. */
    public static final String SKIPPED = "skipped";
}
