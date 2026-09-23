package zelisline.ub.catalog.api.dto;

public record BulkPriceApplyResponse(
        int updated,
        int skippedExisting,
        int unchanged,
        int losses,
        int lowMargin
) {
}
