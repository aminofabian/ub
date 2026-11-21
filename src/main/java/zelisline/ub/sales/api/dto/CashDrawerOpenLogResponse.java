package zelisline.ub.sales.api.dto;

import java.util.List;

public record CashDrawerOpenLogResponse(
        List<CashDrawerOpenLogRow> rows,
        int unmatched,
        List<CashDrawerOpenLogTender> tenders
) {}
