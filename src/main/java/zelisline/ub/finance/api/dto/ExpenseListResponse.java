package zelisline.ub.finance.api.dto;

import java.util.List;

public record ExpenseListResponse(
        List<ExpenseResponse> expenses,
        int totalCount,
        int page,
        int size,
        boolean hasMore
) {
}
