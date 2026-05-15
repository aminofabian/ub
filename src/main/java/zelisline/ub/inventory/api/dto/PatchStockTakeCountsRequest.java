package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PatchStockTakeCountsRequest(
        @NotEmpty @Valid List<LineCounted> lines
) {
    public record LineCounted(
            @NotBlank String lineId,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal countedQty,
            @Size(max = 255) String aisle,
            @Valid List<BatchCounted> batches
    ) {
    }

    public record BatchCounted(
            @NotBlank String batchId,
            @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal countedQty
    ) {
    }
}
