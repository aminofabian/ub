package zelisline.ub.catalog.api.dto;

public record ItemSummaryResponse(
        String id,
        String sku,
        String barcode,
        String name,
        String variantName,
        String categoryId,
        String imageKey,
        /** HTTPS URL for list thumbnails; first gallery image when {@code imageKey} is not a URL. */
        String thumbnailUrl,
        boolean active,
        boolean webPublished,
        String variantOfItemId
) {
}
