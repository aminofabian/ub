package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One sellable option under a POS-created product group. */
public record PosQuickCreateVariantLine(
        @NotBlank @Size(max = 255) String variantName,
        @Size(max = 191) String barcode,
        @NotNull @DecimalMin("0.01") BigDecimal unitPrice,
        /** Optional cost / buying price stored on the variant. */
        @DecimalMin("0.00") BigDecimal buyingPrice,
        /**
         * Opening on-hand qty at the till branch.
         * Defaults to 1 when omitted.
         */
        @DecimalMin("0.0001") BigDecimal initialStockQty
) {
}
