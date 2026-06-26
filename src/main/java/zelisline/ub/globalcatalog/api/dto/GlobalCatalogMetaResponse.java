package zelisline.ub.globalcatalog.api.dto;

import java.util.List;

public record GlobalCatalogMetaResponse(
        String catalogId,
        String catalogName,
        String currency,
        List<GlobalCategoryResponse> categories,
        List<GlobalProductPackSummaryResponse> packs
) {
}
