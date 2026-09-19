package zelisline.ub.payments.api.dto;

/**
 * One STK rail the cashier can pick when sending an M-Pesa prompt.
 */
public record PosStkRailResponse(
        String configId,
        String gatewayType,
        String label,
        String displayName,
        boolean isDefault
) {
}
