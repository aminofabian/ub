package zelisline.ub.globalcatalog.api.dto;

public record ReplaceCatalogEligibilityResponse(
        boolean eligible,
        String blockReason,
        long activeItemCount,
        boolean hasSales,
        boolean hasNonZeroBatches,
        String packId,
        String packName,
        int packProductCount
) {
}
