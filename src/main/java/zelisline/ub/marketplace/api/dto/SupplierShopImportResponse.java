package zelisline.ub.marketplace.api.dto;

/** What copying a supplier's price list into its own shop did. */
public record SupplierShopImportResponse(
        /** Products created in the shop by this call. */
        int copied,
        /** Products that were already in the shop from an earlier copy. */
        int alreadyInShop,
        /** Products that could not be created; each is left for a later try. */
        int failed,
        /** True when the shop's plan has no room for more products and the copy stopped there. */
        boolean stoppedByPlanLimit,
        /** The plan's own message when it stopped the copy; otherwise null. */
        String planMessage
) {
}
