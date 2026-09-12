package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateMarketplaceEscrowHoldRequest(
        @NotBlank String supplierId,
        String purchaseOrderId,
        String supplierInvoiceId,
        @NotNull @DecimalMin("1.00") BigDecimal amount,
        String currency,
        String note,
        String idempotencyKey
) {
}
