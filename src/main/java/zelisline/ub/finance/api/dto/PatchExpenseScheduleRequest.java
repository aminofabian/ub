package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PatchExpenseScheduleRequest(
        String name,
        BigDecimal amount,
        String paymentMethod,
        String frequency,
        LocalDate endDate,
        Boolean active,
        Boolean includeInCashDrawer,
        String branchId,
        String receiptS3Key,
        String expenseLedgerAccountId,
        String automationMode,
        String vendorContactName,
        String vendorPhone,
        String vendorMpesaNumber,
        String vendorLeaseNote
) {
}
