package zelisline.ub.marketplace.api.dto;

/** The supplier's own shop. {@code opened} false means none yet and the other fields are null. */
public record SupplierShopResponse(
        boolean opened,
        String businessId,
        String slug,
        String name,
        /** Full host of the shop, e.g. acme.kiosk.ke; null where no platform domain is configured. */
        String host
) {
    public static SupplierShopResponse none() {
        return new SupplierShopResponse(false, null, null, null, null);
    }
}
