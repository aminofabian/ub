package zelisline.ub.storefront.api.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicCatalogListResponse(
        String currency,
        List<PublicCatalogItemCardResponse> items,
        String nextCursor
) {
}
