package zelisline.ub.tenancy.api.dto;

public record StocktakePatchRequest(
        Boolean showSystemStockToStockManager,
        Integer dailyAuditSampleSize,
        String morningStartsAt,
        String eveningStartsAt,
        String countingEndsAt
) {
}
