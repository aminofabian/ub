package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.Pattern;

public record ProfilePatchRequest(
        @Pattern(regexp = "mini-mart|full-grocery|fresh-market|mixed-shop") String storeType
) {
}
