package zelisline.ub.marketplace.api.dto;

public record MarketplaceAttachResponse(
        String connectionId,
        String localSupplierId,
        String marketplaceSupplierId,
        String supplierNumber,
        String supplierName,
        int linkedExisting,
        int createdItems,
        int alreadyLinked,
        int skipped,
        String status
) {
    public int importedProductLinks() {
        return linkedExisting + createdItems;
    }
}
