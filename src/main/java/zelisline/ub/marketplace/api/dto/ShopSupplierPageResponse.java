package zelisline.ub.marketplace.api.dto;

/** A supplier shop's marketplace page, as its owner manages it from inside the shop. */
public record ShopSupplierPageResponse(
        SupplierPortalProfileResponse profile,
        boolean listed,
        boolean hidePrices,
        boolean hideContacts
) {
}
