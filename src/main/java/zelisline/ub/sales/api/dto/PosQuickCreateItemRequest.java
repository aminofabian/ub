package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Cashier quick-create: new sellable SKU (+ optional shelf price) without full catalog UI. */
public record PosQuickCreateItemRequest(
        @NotBlank @Size(max = 500) String name,
        @NotBlank @Size(max = 36) String itemTypeId,
        @Size(max = 191) String barcode,
        @Size(max = 36) String categoryId,
        @Size(max = 16) String unitType,
        @Size(max = 36) String branchId,
        @NotNull @DecimalMin("0.01") BigDecimal unitPrice,
        /** Optional cost / buying price stored on the item. */
        @DecimalMin("0.00") BigDecimal buyingPrice,
        /**
         * Opening on-hand qty at the till branch so the item can be sold immediately.
         * Defaults to 1 when omitted.
         */
        @DecimalMin("0.0001") BigDecimal initialStockQty
) {
}
