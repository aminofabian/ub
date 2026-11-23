package zelisline.ub.suppliers.api.dto;

/**
 * What one connected supplier may see of this shop's records, read from the
 * {@code business_supplier_connections} row that joins them.
 *
 * <p>{@code connected} is false when the local supplier has no marketplace connection; the flags are then
 * the safe defaults (nothing shared) and are not saved anywhere.
 */
public record SupplierPortalVisibilityResponse(
        boolean connected,
        String status,
        boolean canViewPurchaseHistory,
        boolean canViewStockLevels,
        boolean canViewLowStockAlerts,
        boolean canViewSalesVelocity,
        boolean canViewDemandForecast,
        boolean canSuggestRestock,
        boolean canCreateDraftPo
) {

    /** No connection: the supplier cannot see anything of this shop. */
    public static SupplierPortalVisibilityResponse notConnected() {
        return new SupplierPortalVisibilityResponse(
                false, null, false, false, false, false, false, false, false);
    }
}
