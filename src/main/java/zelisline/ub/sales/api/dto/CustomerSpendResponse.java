package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record CustomerSpendResponse(
        LocalDate asOf,
        long identifiedCustomerCount,
        long identifiedSaleCount,
        BigDecimal identifiedSpend,
        long walkInSaleCount,
        BigDecimal walkInSpend,
        boolean truncated,
        List<CustomerSpendRow> rows
) {
}
