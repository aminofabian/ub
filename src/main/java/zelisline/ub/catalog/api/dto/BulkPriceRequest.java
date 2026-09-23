package zelisline.ub.catalog.api.dto;

import java.util.List;

import jakarta.validation.Valid;

/**
 * Either an explicit id list, or every item matching the same filters as the catalog list
 * (including items that are not on the current page).
 */
public record BulkPriceRequest(
        List<String> itemIds,
        Boolean selectAllMatching,
        List<String> excludedItemIds,
        String search,
        String barcode,
        String categoryId,
        Boolean includeCategoryDescendants,
        Boolean noBarcode,
        Boolean includeInactive,
        Boolean inactiveOnly,
        Boolean noPrice,
        Boolean zeroStock,
        Boolean lowStock,
        CatalogListScope catalogScope,
        List<CatalogRowType> catalogRowTypes,
        String branchId,
        String itemTypeId,
        String aisleId,
        Boolean aisleUnset,
        PriceStatusFilter priceStatus,
        @Valid BulkPriceSideRequest buying,
        @Valid BulkPriceSideRequest selling,
        PriceRounding rounding,
        Boolean acknowledgeLosses
) {
    public BulkPriceRequest {
        selectAllMatching = Boolean.TRUE.equals(selectAllMatching);
        includeCategoryDescendants = Boolean.TRUE.equals(includeCategoryDescendants);
        noBarcode = Boolean.TRUE.equals(noBarcode);
        includeInactive = Boolean.TRUE.equals(includeInactive);
        inactiveOnly = Boolean.TRUE.equals(inactiveOnly);
        noPrice = Boolean.TRUE.equals(noPrice);
        zeroStock = Boolean.TRUE.equals(zeroStock);
        lowStock = Boolean.TRUE.equals(lowStock);
        aisleUnset = Boolean.TRUE.equals(aisleUnset);
        acknowledgeLosses = Boolean.TRUE.equals(acknowledgeLosses);
    }
}
