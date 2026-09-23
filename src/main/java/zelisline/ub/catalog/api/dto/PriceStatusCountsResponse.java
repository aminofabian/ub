package zelisline.ub.catalog.api.dto;

public record PriceStatusCountsResponse(
        long missingBuying,
        long missingSelling,
        long bothMissing,
        long bothSet
) {
    public static PriceStatusCountsResponse zeros() {
        return new PriceStatusCountsResponse(0, 0, 0, 0);
    }
}
