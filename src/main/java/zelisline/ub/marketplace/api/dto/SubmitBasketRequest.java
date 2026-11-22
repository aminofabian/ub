package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** One basket, several suppliers. Each supplier's lines become one order in that supplier's shop. */
public record SubmitBasketRequest(
        @NotBlank @Size(max = 36) String buyerBusinessId,
        @NotBlank @Size(max = 255) String buyerName,
        @Size(max = 2000) String notes,
        @NotEmpty @Size(max = 10) @Valid List<Supplier> suppliers
) {
    public record Supplier(
            @NotBlank @Size(max = 36) String supplierId,
            @NotEmpty @Size(max = 50) @Valid List<Line> lines
    ) {
    }

    public record Line(
            @NotBlank @Size(max = 36) String productId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity
    ) {
    }
}
