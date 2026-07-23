package zelisline.ub.globalcatalog.api.dto;

public record GlobalProductImageResponse(
        String id,
        String imageUrl,
        int sortOrder,
        String altText,
        Integer width,
        Integer height
) {
}
