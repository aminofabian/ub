package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cashier quick-create: new sellable SKU (+ optional shelf price) without full catalog UI. */
public record PosQuickCreateItemRequest(
        @NotBlank @Size(max = 500) String name,
        @NotBlank @Size(max = 36) String itemTypeId,
        @Size(max = 191) String barcode,
        @Size(max = 36) String categoryId,
        @Size(max = 16) String unitType,
        @Size(max = 36) String branchId,
        /**
         * Shelf price for a standalone / linked variant.
         * Ignored when {@code createAsGroup} — each entry in {@code variants} has its own price.
         */
        @DecimalMin("0.01") BigDecimal unitPrice,
        /** Optional cost / buying price stored on the item. */
        @DecimalMin("0.00") BigDecimal buyingPrice,
        /**
         * Opening on-hand qty at the till branch so the item can be sold immediately.
         * Defaults to 1 when omitted. Ignored when {@code createAsGroup}.
         */
        @DecimalMin("0.0001") BigDecimal initialStockQty,
        /**
         * Optional existing catalog item to link as a variant under.
         * Pass a parent (creates a child) or another variant (creates a sibling under the same parent).
         * Not allowed with {@code createAsGroup}.
         */
        @Size(max = 36) String relatedItemId,
        /**
         * Option label for the new variant (e.g. "500ml", "Tray").
         * Defaults to {@code name} when linking and this is blank.
         */
        @Size(max = 255) String variantName,
        /**
         * When true, create a non-sellable parent group named {@code name}, then create each
         * entry in {@code variants} under it. Returns the first variant (sellable SKU).
         */
        Boolean createAsGroup,
        /** Required (min 1) when {@code createAsGroup} is true. Max 24 options. */
        @Valid @Size(max = 24) List<PosQuickCreateVariantLine> variants
) {
}
