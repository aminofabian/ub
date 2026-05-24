package zelisline.ub.grocery.api.dto;

import java.util.List;

public record GroceryInvoiceListResponse(
        List<GroceryInvoiceSummaryResponse> invoices
) {
}
