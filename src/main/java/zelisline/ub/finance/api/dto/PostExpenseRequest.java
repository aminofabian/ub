package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PostExpenseRequest(
        @NotNull LocalDate expenseDate,
        @NotBlank String name,
        @NotBlank String categoryType,
        @NotNull BigDecimal amount,
        @NotBlank String paymentMethod,
        @NotNull Boolean includeInCashDrawer,
        String branchId,
        String receiptS3Key,
        String expenseLedgerAccountId,
        Instant paidAt
) {
}

