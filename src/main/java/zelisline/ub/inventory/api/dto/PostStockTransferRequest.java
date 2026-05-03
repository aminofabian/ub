package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record PostStockTransferRequest(
        @NotBlank String fromBranchId,
        @NotBlank String toBranchId,
        String notes,
        @NotEmpty @Valid List<Line> lines
) {
    public record Line(
            @NotBlank String itemId,
            @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity
    ) {
    }
}
