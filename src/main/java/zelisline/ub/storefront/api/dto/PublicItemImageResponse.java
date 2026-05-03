package zelisline.ub.storefront.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicItemImageResponse(
        String url,
        String altText,
        Integer width,
        Integer height
) {
}
