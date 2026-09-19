package zelisline.ub.payments.api.dto;

/**
 * Tenant-facing readiness for till/paybill-only M-Pesa (Model B).
 * {@code message} explains why it is unavailable when {@code available} is false.
 */
public record MpesaCustodyAvailabilityResponse(
        boolean available,
        String provider,
        String message
) {
}
