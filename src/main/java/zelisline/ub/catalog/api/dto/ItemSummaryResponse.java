package zelisline.ub.catalog.api.dto;

public record ItemSummaryResponse(
        String id,
        String sku,
        String barcode,
        String name,
        String variantName,
        String imageKey,
        boolean active,
        String variantOfItemId
) {
}
