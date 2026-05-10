package zelisline.ub.sales.api.dto;

import java.util.List;

/**
 * Paginated shift list response.
 */
public record ShiftListResponse(
        List<ShiftListItemResponse> shifts,
        int totalCount,
        int page,
        int size,
        boolean hasMore
) {
}
