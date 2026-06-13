package zelisline.ub.grocery.api.dto;

public record IssueGroceryDraftResponse(
        String draftId,
        long counterNumber,
        String status,
        String invoiceId,
        GroceryInvoiceResponse invoice,
        boolean createdNew
) {
}
