package zelisline.ub.grocery.api.dto;

public record RemoteInvoiceStkResponse(
        String invoiceId,
        String checkoutRequestId,
        String status,
        String message,
        boolean accepted
) {
}
