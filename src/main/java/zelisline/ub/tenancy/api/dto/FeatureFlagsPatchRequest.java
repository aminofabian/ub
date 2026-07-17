package zelisline.ub.tenancy.api.dto;

import jakarta.validation.Valid;

public record FeatureFlagsPatchRequest(
        @Valid PosDraftsFeatureFlagsPatch posDrafts,
        /** Enable the butcher counter POS workspace and weighed-sale features. */
        Boolean butcherPosEnabled,
        /** Allow cashiers to override shelf prices at POS. */
        Boolean posCashierPriceEdit,
        /** Allow cashiers to quick-create products at POS. */
        Boolean posCashierCreateProduct,
        /** Allow cashiers to mark items as sold by weight from the POS cart. */
        Boolean posCashierWeighedToggle,
        /** Prefill opening float denominations from the previous night's closing count. */
        Boolean shiftsPrefillOpeningFromLastClose
) {
}
