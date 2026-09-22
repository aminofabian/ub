package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record PostProfitPocketRequest(
        @NotNull LocalDate periodFrom,
        @NotNull LocalDate periodTo,
        String branchId,
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        BigDecimal leaveFloat,
        /** cash | mpesa_manual | bank — which asset account to credit. */
        String fundingMethod,
        /** Soft warnings the client showed (e.g. negative_gp, above_surplus). */
        List<String> acknowledgedWarnings
) {
}
