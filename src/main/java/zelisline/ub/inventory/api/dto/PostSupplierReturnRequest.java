package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Outbound return of unused stock to a supplier. Depletes the shelf batches and nets the on-hand
 * so returned goods stop being counted; inventory-only (no supplier credit / GL is posted).
 */
public record PostSupplierReturnRequest(
        @NotBlank String branchId,
        @NotBlank String itemId,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
        /** Optional supplier reference recorded on the movement audit row. */
        String supplierId,
        /** Optional specific batch to return; when null the system writes off FEFO/FIFO. */
        String batchId,
        @Size(max = 255) String notes
) {
}
