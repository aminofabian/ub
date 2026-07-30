package zelisline.ub.tenancy.api.dto;

/**
 * When M-Pesa Buy Goods till listening starts on POS / storefront.
 * Absent flags keep documented defaults (checkout + storefront on).
 */
public record PosTillListenFeatureFlagsPatch(
        /** Listen when cashier opens checkout / pay drawer. Default true. */
        Boolean checkout,
        /** Listen on any open cart tab with a total (before checkout). Default false. */
        Boolean openCart,
        /** Listen when M-Pesa tender is selected. Default false. */
        Boolean mpesaSelected,
        /** Listen on storefront cart preview and checkout. Default true. */
        Boolean storefront
) {
}
