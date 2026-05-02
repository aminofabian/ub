package zelisline.ub.catalog.api.dto;

public record AisleResponse(
        String id,
        String name,
        String code,
        int sortOrder,
        boolean active
) {
}
