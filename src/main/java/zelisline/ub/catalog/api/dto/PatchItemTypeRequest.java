package zelisline.ub.catalog.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Size;

public record PatchItemTypeRequest(
        @Size(max = 191) String key,
        @Size(max = 255) String label,
        @Size(max = 500) String icon,
        @Size(max = 64) String color,
        Integer sortOrder,
        Boolean active,
        @JsonProperty("isDefault") Boolean isDefault
) {
}
