package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

public record PostSupplierPaymentResponse(
        String supplierPaymentId,
        String journalEntryId,
        BigDecimal totalAllocated,
        BigDecimal supplierPrepaymentBalanceAfter
) {
}
