package zelisline.ub.globalcatalog.api.dto;

import java.util.List;

/**
 * One slice of a starter pack, so a big pack (thousands of medicines) can be shown as it loads.
 * {@code total} counts every product in the pack; {@code nextOffset} is where the next slice
 * starts and {@code done} is true when there is none. {@code products} can hold fewer rows than
 * the slice covered when products the shop already has are left out.
 */
public record GlobalProductPackSliceResponse(
        String id,
        String name,
        int total,
        int offset,
        int nextOffset,
        boolean done,
        List<GlobalProductResponse> products
) {
}
