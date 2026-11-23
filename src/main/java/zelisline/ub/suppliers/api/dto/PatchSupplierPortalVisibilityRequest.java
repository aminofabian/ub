package zelisline.ub.suppliers.api.dto;

/**
 * Partial update of what a connected supplier may see. A null field is left as it is.
 *
 * <p>Only the flags that a portal surface actually reads today are exposed by the shop's screen
 * (purchase history, stock levels, sales velocity). The rest are accepted here so a shop can grant them
 * ahead of the surface that will use them, and so the contract does not change when one lands.
 */
public record PatchSupplierPortalVisibilityRequest(
        Boolean canViewPurchaseHistory,
        Boolean canViewStockLevels,
        Boolean canViewLowStockAlerts,
        Boolean canViewSalesVelocity,
        Boolean canViewDemandForecast,
        Boolean canSuggestRestock,
        Boolean canCreateDraftPo
) {
}
