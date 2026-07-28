package zelisline.ub.marketplace.api.dto;

public record MarketplaceSupplierStatsResponse(
        long total,
        long active,
        long draft,
        long suspended,
        long withPortalUsers,
        long withLinkedShops,
        long needingInvite
) {
}
