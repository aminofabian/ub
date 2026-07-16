package zelisline.ub.tenancy.api.dto;

public record SuppliersAccessPatchRequest(
        Boolean allowSupplierWriteForStockManager,
        Boolean allowSupplierWriteForCashier,
        Boolean allowLinkProductsForStockManager,
        Boolean allowLinkProductsForCashier
) {
}
