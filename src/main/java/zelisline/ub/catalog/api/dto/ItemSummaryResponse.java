package zelisline.ub.catalog.api.dto;

public record ItemSummaryResponse(
        String id,
        String sku,
        String barcode,
        String name,
        String variantName,
        String categoryId,
        String categoryName,
        String imageKey,
        /** HTTPS URL for list thumbnails; first gallery image when {@code imageKey} is not a URL. */
        String thumbnailUrl,
        boolean active,
        boolean webPublished,
        String variantOfItemId,
        /**
         * When true, this row is a parent item that only groups variant SKUs (not a sellable line on its own).
         * Always false for variant rows and for standalone products.
         */
        boolean groupLabelOnly
) {
}
