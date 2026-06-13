package zelisline.ub.tenancy.api.dto;

public record StockLevelsPatchRequest(
        Boolean allowStockEditForStockManager,
        Boolean allowStockEditForGroceryClerk
) {
}
