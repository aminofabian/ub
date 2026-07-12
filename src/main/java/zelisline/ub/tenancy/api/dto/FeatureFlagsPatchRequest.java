package zelisline.ub.tenancy.api.dto;

import jakarta.validation.Valid;

public record FeatureFlagsPatchRequest(
        @Valid PosDraftsFeatureFlagsPatch posDrafts,
        /** Enable the butcher counter POS workspace and weighed-sale features. */
        Boolean butcherPosEnabled,
        /** Allow cashiers to override shelf prices at POS. */
        Boolean posCashierPriceEdit,
        /** Allow cashiers to quick-create products at POS. */
        Boolean posCashierCreateProduct
) {
}
