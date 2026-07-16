package zelisline.ub.tenancy.api.dto;

public record ReceiveStockPatchRequest(
        Boolean allowReceiveForCashier,
        Boolean allowReceiveForStockManager
) {
}
