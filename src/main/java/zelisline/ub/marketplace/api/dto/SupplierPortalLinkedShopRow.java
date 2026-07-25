package zelisline.ub.marketplace.api.dto;

public record SupplierPortalLinkedShopRow(
        String connectionId,
        String businessId,
        String shopName,
        String localSupplierId,
        String localSupplierName,
        String status
) {
}
