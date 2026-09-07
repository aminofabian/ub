package zelisline.ub.catalog.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Nest existing standalone products under an existing parent as option variants.
 * Does not create new SKUs — each {@code itemId} is reparented in place.
 */
public record AttachVariantsRequest(
        @NotEmpty @Size(max = 200) @Valid List<AttachVariantLineRequest> items,
        /**
         * When true (default), mark the parent non-sellable so cashiers sell the
         * attached option SKUs (typical “Dry Hook” family label).
         */
        Boolean makeParentNonSellable
) {
}
