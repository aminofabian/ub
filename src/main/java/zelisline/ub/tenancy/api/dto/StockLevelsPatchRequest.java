package zelisline.ub.tenancy.api.dto;

public record StockLevelsPatchRequest(
        Boolean allowStockEditForStockManager,
        Boolean allowStockEditForGroceryClerk,
        Boolean allowNegativeStock,
        Boolean allowActivityForStockManager,
        Boolean allowStockPageForStockManager,
        Boolean allowSpoilsForGroceryClerk,
        Boolean allowMinStockForGroceryClerk,
        Boolean allowOrderPadForGroceryClerk,
        Boolean allowOrderConfirmForGroceryClerk
) {
}
