package zelisline.ub.catalog.api.dto;

public record CategorySupplierSummaryResponse(
        String supplierId,
        String supplierName,
        int sortOrder,
        boolean primary
) {
}
