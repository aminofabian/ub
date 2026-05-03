package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ExpenseResponse(
        String id,
        String branchId,
        LocalDate expenseDate,
        String name,
        String categoryType,
        BigDecimal amount,
        String paymentMethod,
        boolean includeInCashDrawer,
        String receiptS3Key,
        String expenseLedgerAccountId,
        String journalEntryId,
        String createdBy,
        Instant createdAt
) {
}

