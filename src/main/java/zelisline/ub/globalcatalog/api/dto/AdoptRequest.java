package zelisline.ub.globalcatalog.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record AdoptRequest(
        @NotBlank @Size(max = 36) String openingBranchId,
        @NotEmpty @Valid List<AdoptLineRequest> lines,
        /** When true, create tenant categories from global {@code tenantCategorySlugHint}. Default false. */
        Boolean createMissingCategories,
        /** Optional pack id when adopt was started from a starter pack (telemetry). */
        @Size(max = 36) String packId
) {
}
