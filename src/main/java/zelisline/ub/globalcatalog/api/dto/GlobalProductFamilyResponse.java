package zelisline.ub.globalcatalog.api.dto;

import java.util.List;

/**
 * The published family around one global product, for the tenant add-product flow:
 * {@code root} is the base product (the product itself, or its parent when the looked-up
 * product is a variant), {@code variants} are the root's published variants. Lets the UI
 * offer "also add its pack sizes" the moment a match is applied.
 */
public record GlobalProductFamilyResponse(
        GlobalProductResponse root,
        List<GlobalProductResponse> variants
) {
}
