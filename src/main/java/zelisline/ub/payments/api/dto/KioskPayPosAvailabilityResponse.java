package zelisline.ub.payments.api.dto;

/**
 * Lightweight POS flag — cashiers can call this without gateway settings permission.
 */
public record KioskPayPosAvailabilityResponse(
        boolean available,
        boolean platformEnabled,
        boolean accountActive,
        boolean stkConfigured,
        String currency,
        String reason
) {
}
