package zelisline.ub.sales.api.dto;

import java.util.List;

public record CustomerItemRhythmResponse(
        String customerId,
        long linkedSaleCount,
        List<CustomerItemRhythmRow> rows
) {
}
