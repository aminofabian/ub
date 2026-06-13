package zelisline.ub.grocery.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record CreateGroceryDraftRequest(
        @NotBlank String branchId,
        @NotBlank String clientDraftId,
        @NotEmpty @Valid List<GroceryDraftLineInput> lines
) {
}
