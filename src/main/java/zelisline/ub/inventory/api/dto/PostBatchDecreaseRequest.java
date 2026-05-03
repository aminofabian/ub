package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PostBatchDecreaseRequest(
        @NotBlank String batchId,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
        @Size(max = 255) String reason
) {
}
