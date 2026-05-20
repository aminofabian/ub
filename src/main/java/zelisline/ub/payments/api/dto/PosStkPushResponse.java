package zelisline.ub.payments.api.dto;

public record PosStkPushResponse(
        boolean accepted,
        String checkoutRequestId,
        String message,
        String responseCode
) {
}
