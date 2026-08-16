package zelisline.ub.sales.api.dto;

import java.util.List;

public record CustomerTrendResponse(
        long totalDistinct,
        List<MonthlyCustomerRow> months
) {}
