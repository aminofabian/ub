package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One more pack a supplier sells a product in, e.g. 12 per carton at 1,100. */
public record AddSupplierPortalPackRequest(
        @NotNull @DecimalMin("0.0001") BigDecimal packSize,
        @Size(max = 32) String packUnit,
        @NotNull @DecimalMin("0.01") BigDecimal unitPrice
) {
}
