package zelisline.ub.grocery.api.dto;

import java.util.List;

public record GroceryDraftListResponse(
        List<GroceryDraftSummaryResponse> drafts
) {
}
