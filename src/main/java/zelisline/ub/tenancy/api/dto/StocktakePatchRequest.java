package zelisline.ub.tenancy.api.dto;

public record StocktakePatchRequest(
        Boolean showSystemStockToStockManager,
        Integer dailyAuditSampleSize,
        String morningStartsAt,
        String morningEndsAt,
        String eveningStartsAt,
        String eveningEndsAt
) {
}
