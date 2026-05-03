package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExpenseScheduleResponse(
        String id,
        String branchId,
        String name,
        String categoryType,
        BigDecimal amount,
        String paymentMethod,
        String frequency,
        LocalDate startDate,
        LocalDate endDate,
        boolean active,
        boolean includeInCashDrawer,
        String receiptS3Key,
        String expenseLedgerAccountId,
        LocalDate lastGeneratedOn,
        String createdBy
) {
}

