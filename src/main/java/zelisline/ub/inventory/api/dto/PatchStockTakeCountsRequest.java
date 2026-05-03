package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record PatchStockTakeCountsRequest(
        @NotEmpty @Valid List<LineCounted> lines
) {
    public record LineCounted(
            @NotBlank String lineId,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal countedQty
    ) {
    }
}
