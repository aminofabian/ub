package zelisline.ub.payments.api.dto;

public record PosTillAwaitResponse(
        boolean accepted,
        String checkoutRequestId,
        String message
) {
}
