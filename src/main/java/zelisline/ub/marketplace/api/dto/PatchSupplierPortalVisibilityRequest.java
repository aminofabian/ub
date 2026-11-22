package zelisline.ub.marketplace.api.dto;

/** A null field is left as it is. */
public record PatchSupplierPortalVisibilityRequest(
        Boolean hidePrices,
        Boolean hideContacts
) {
}
