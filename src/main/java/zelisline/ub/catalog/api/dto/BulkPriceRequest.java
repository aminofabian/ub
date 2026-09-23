package zelisline.ub.catalog.api.dto;

import java.util.List;

import jakarta.validation.Valid;

/**
 * Either an explicit id list, or every item matching the same filters as the catalog list
 * (including items that are not on the current page).
 */
public record BulkPriceRequest(
        List<String> itemIds,
        boolean selectAllMatching,
        List<String> excludedItemIds,
        String search,
        String barcode,
        String categoryId,
        boolean includeCategoryDescendants,
        boolean noBarcode,
        boolean includeInactive,
        boolean inactiveOnly,
        boolean noPrice,
        boolean zeroStock,
        boolean lowStock,
        CatalogListScope catalogScope,
        List<CatalogRowType> catalogRowTypes,
        String branchId,
        String itemTypeId,
        String aisleId,
        boolean aisleUnset,
        PriceStatusFilter priceStatus,
        @Valid BulkPriceSideRequest buying,
        @Valid BulkPriceSideRequest selling,
        PriceRounding rounding,
        boolean acknowledgeLosses
) {
}
