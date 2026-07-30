package zelisline.ub.storefront.api.dto;

public record PublicTillAwaitResponse(
        boolean accepted,
        boolean listenEnabled,
        String checkoutRequestId,
        String message
) {
}
