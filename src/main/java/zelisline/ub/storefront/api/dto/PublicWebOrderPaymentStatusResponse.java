package zelisline.ub.storefront.api.dto;

public record PublicWebOrderPaymentStatusResponse(
        String orderStatus,
        boolean paid,
        boolean paymentFailed,
        String checkoutRequestId,
        String failureReason
) {
}
