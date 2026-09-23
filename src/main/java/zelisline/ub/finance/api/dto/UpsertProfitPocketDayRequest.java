package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpsertProfitPocketDayRequest(
        @NotNull LocalDate date,
        String branchId,
        @NotNull @DecimalMin("0.00") BigDecimal pocketedAmount,
        @Size(max = 500) String note,
        Boolean allowAboveProfit,
        Boolean refreshProfit
) {
}
