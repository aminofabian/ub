package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CategoryDailyRevenueRow(
        LocalDate date,
        BigDecimal grossRevenue,
        BigDecimal refundAmount,
        BigDecimal netRevenue,
        BigDecimal netProfit
) {}
