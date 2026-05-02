package zelisline.ub.purchasing.api.dto;

public record SingleSourceRiskRow(
        String itemId,
        String sku,
        String name,
        String soleSupplierId,
        String soleSupplierName
) {
}
