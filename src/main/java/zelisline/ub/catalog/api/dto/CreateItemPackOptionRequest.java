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
        Boolean active
) {
}
