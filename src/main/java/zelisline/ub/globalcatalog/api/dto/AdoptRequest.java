package zelisline.ub.globalcatalog.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record AdoptRequest(
        @NotBlank @Size(max = 36) String openingBranchId,
        @NotEmpty @Valid List<AdoptLineRequest> lines
) {
}
