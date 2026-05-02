package zelisline.ub.catalog.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateItemTypeRequest(
        @NotBlank @Size(max = 191) @JsonProperty("key") String key,
        @NotBlank @Size(max = 255) String label,
        @Size(max = 500) String icon,
        @Size(max = 64) String color,
        Integer sortOrder
) {
}
