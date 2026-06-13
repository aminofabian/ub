package zelisline.ub.grocery.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record PatchGroceryDraftLinesRequest(
        @NotEmpty @Valid List<GroceryDraftLineInput> lines,
        Long expectedVersion
) {
}
