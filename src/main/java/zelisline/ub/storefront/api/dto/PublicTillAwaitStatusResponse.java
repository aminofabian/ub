package zelisline.ub.storefront.api.dto;

public record PublicTillAwaitStatusResponse(
        String status,
        String checkoutRequestId,
        String gatewayTransactionId,
        String failureReason,
        boolean success,
        boolean failed,
        boolean pending
) {
}
