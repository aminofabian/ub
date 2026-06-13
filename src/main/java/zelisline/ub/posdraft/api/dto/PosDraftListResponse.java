package zelisline.ub.posdraft.api.dto;

import java.util.List;

public record PosDraftListResponse(
        List<PosDraftSummaryResponse> drafts
) {
}
