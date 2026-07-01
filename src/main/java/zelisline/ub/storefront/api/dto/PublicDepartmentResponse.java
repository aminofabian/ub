package zelisline.ub.storefront.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicDepartmentResponse(
        String id,
        String label,
        /** Emoji / icon key, or HTTPS URL for a custom icon image. */
        String icon,
        Long itemCount
) {
}
