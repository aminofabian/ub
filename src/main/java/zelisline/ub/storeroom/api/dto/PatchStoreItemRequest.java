package zelisline.ub.storeroom.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record PatchStoreItemRequest(
        @Size(max = 255) String name,
        @Size(max = 191) String barcode,
        @Min(0) Integer quantity,
        LocalDate expiryDate,
        Boolean clearExpiryDate,
        @DecimalMin(value = "0.0", inclusive = true) BigDecimal buyingPrice,
        Boolean clearBuyingPrice
) {
}
