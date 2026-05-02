package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateBranchRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 500) String address
) {
}
