package zelisline.ub.tenancy.api.dto;

import java.util.List;

import jakarta.validation.constraints.Pattern;

public record ProfilePatchRequest(
        @Pattern(regexp = StoreTypeCodes.PATTERN) String storeType,
        List<@Pattern(regexp = StoreTypeCodes.PATTERN) String> storeTypes
) {
}
