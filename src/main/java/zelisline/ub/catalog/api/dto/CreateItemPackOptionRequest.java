package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateItemPackOptionRequest(
        @Size(max = 255) String label,
        @NotBlank @Size(max = 32) String packUnit,
        @DecimalMin(value = "1.0001", message = "unitsPerPack must be greater than 1") BigDecimal unitsPerPack,
        @DecimalMin("0") BigDecimal defaultPackPrice,
        @Size(max = 191) String barcode,
        @Size(max = 64) String skuSuffix,
        Integer sortOrder,
        Boolean active,
        /** Optional inner package name ("packet", "inner carton") for two-level packaging. */
        @Size(max = 32) String innerPackUnit,
        /** Inner packages per purchase unit; {@code × baseUnitsPerInner} must equal unitsPerPack. */
        @DecimalMin(value = "1.0001", message = "innerPackCount must be greater than 1") BigDecimal innerPackCount,
        /** Base items per inner package. */
        @DecimalMin(value = "0.0001", message = "baseUnitsPerInner must be positive") BigDecimal baseUnitsPerInner,
        /** Pre-select this purchase unit on new receipts. At most one per item. */
        Boolean defaultPurchase
) {

    /** Backwards-compatible constructor for callers that predate nested/default packaging. */
    public CreateItemPackOptionRequest(
            String label,
            String packUnit,
            BigDecimal unitsPerPack,
            BigDecimal defaultPackPrice,
            String barcode,
            String skuSuffix,
            Integer sortOrder,
            Boolean active
    ) {
        this(label, packUnit, unitsPerPack, defaultPackPrice, barcode, skuSuffix, sortOrder, active,
                null, null, null, null);
    }
}
