package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PostStandaloneWastageRequest(
        @NotBlank String branchId,
        @NotBlank String itemId,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal unitCost,
        @Size(max = 255) String reason,
        // NEW — if provided, deplete this specific batch.
        // If null, the system auto-picks the most eligible batch (FEFO → FIFO).
        String batchId,
        // NEW — enum value: SPOILAGE, BREAKAGE, THEFT, etc.
        String wastageReason
) {
}
