package zelisline.ub.catalog.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ItemTypeResponse(
        String id,
        @JsonProperty("key") String key,
        String label,
        String icon,
        String color,
        int sortOrder,
        boolean active
) {
}
