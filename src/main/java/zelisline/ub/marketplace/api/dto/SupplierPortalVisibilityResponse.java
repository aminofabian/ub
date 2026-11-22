package zelisline.ub.marketplace.api.dto;

/** What a supplier keeps off its public pages. The supplier itself always stays listed. */
public record SupplierPortalVisibilityResponse(
        boolean hidePrices,
        boolean hideContacts
) {
}
