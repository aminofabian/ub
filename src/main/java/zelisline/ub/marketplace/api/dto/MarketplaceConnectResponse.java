package zelisline.ub.marketplace.api.dto;

public record MarketplaceConnectResponse(
        String connectionId,
        String localSupplierId,
        String marketplaceSupplierId,
        String supplierName,
        int importedProductLinks,
        String status
) {
}
