package zelisline.ub.purchasing.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;

public record CreatePathAPurchaseOrderRequest(
        @NotBlank String supplierId,
        @NotBlank String branchId,
        LocalDate expectedDate,
        String poNumber,
        String notes
) {
}
