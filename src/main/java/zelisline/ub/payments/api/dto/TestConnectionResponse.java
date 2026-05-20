package zelisline.ub.payments.api.dto;

/**
 * Response for {@code POST /api/v1/payments/gateways/{id}/test}.
 */
public record TestConnectionResponse(
        boolean success,
        String newStatus,
        String errorCode,
        String errorMessage
) {
}
