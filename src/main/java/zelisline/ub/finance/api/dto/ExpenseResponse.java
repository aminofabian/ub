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
        String source,
        String categoryCode,
        BigDecimal amount,
        String paymentMethod,
        String vendorMpesaNumber,
        Instant paidAt,
        boolean includeInCashDrawer,
        String approvalStatus,
        String approvedBy,
        Instant approvedAt,
        String receiptS3Key,
        String expenseLedgerAccountId,
        String journalEntryId,
        String createdBy,
        Instant createdAt
) {
}
