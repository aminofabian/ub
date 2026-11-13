package zelisline.ub.serviceops.api.dto;

public record ServiceCategoryResponse(
        String id,
        String name,
        int sortOrder,
        boolean active
) {
}
