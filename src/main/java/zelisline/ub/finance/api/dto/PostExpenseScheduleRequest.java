package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PostExpenseScheduleRequest(
        @NotBlank String name,
        @NotBlank String categoryType,
        @NotNull BigDecimal amount,
        @NotBlank String paymentMethod,
        @NotBlank String frequency,
        @NotNull LocalDate startDate,
        LocalDate endDate,
        @NotNull Boolean includeInCashDrawer,
        String branchId,
        String receiptS3Key,
        String expenseLedgerAccountId
) {
}

