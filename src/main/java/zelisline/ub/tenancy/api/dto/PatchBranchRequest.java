package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.Size;

public record PatchBranchRequest(
        @Size(max = 255) String name,
        @Size(max = 500) String address,
        Boolean active
) {
}
