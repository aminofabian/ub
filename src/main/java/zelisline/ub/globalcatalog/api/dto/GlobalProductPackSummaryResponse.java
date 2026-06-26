package zelisline.ub.globalcatalog.api.dto;

public record GlobalProductPackSummaryResponse(
        String id,
        String code,
        String name,
        String description,
        int productCount,
        int sortOrder
) {
}
