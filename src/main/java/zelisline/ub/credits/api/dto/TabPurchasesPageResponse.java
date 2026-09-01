package zelisline.ub.credits.api.dto;

import java.util.List;

public record TabPurchasesPageResponse(
        List<TabPurchaseRowResponse> rows,
        int offset,
        int limit,
        boolean hasMore
) {
}
