package zelisline.ub.marketplace.api.dto;

public record SupplierPortalLinkCandidateRow(
        String localSupplierId,
        String businessId,
        String shopName,
        String supplierName,
        String matchReason
) {
}
