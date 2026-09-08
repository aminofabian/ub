package zelisline.ub.storeroom.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateStoreItemRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 191) String barcode,
        @NotNull @Min(0) Integer quantity,
        LocalDate expiryDate,
        @DecimalMin(value = "0.0", inclusive = true) BigDecimal buyingPrice
) {
}
