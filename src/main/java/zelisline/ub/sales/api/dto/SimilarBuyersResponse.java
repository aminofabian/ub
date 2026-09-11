package zelisline.ub.sales.api.dto;

import java.util.List;

public record SimilarBuyersResponse(
        String itemId,
        String matchBasis,
        String matchLabel,
        String hint,
        List<CustomerProductSegmentRow> rows
) {
}
