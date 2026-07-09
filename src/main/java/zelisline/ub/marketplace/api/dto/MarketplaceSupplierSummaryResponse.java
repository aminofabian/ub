package zelisline.ub.marketplace.api.dto;

public record MarketplaceSupplierSummaryResponse(
        String id,
        String name,
        String description,
        String contactEmail,
        String status
) {
}
