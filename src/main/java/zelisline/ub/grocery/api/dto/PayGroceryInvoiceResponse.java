package zelisline.ub.grocery.api.dto;

import java.time.Instant;

import zelisline.ub.sales.api.dto.SaleResponse;

public record PayGroceryInvoiceResponse(
        String invoiceId,
        String saleId,
        String status,
        Instant paidAt,
        SaleResponse receipt
) {
}
