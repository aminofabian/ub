package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ItemTypeResponse(
        String id,
        @JsonProperty("key") String key,
        String label,
        String icon,
        String color,
        int sortOrder,
        boolean active,
        @JsonProperty("isDefault") boolean isDefault,
        BigDecimal defaultMarkupPct
) {
}
