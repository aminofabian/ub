package zelisline.ub.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One existing catalog item to nest under a parent as an option variant.
 * The item keeps its id, SKU, stock, and sales history — only the family link changes.
 */
public record AttachVariantLineRequest(
        @NotBlank @Size(max = 36) String itemId,
        /** Option label shown under the family (e.g. "#12", "500ml"). */
        @NotBlank @Size(max = 255) String variantName
) {
}
