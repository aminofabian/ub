package zelisline.ub.grocery.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record CreateGroceryInvoiceRequest(
        @NotBlank String branchId,
        @NotEmpty @Valid List<CreateGroceryInvoiceLineRequest> lines,
        String notes
) {
}
