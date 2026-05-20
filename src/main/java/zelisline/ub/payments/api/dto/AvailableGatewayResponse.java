package zelisline.ub.payments.api.dto;

/**
 * Response for {@code GET /api/v1/payments/gateways/available}.
 *
 * <p>Each entry represents a gateway type the tenant <em>can</em> configure.
 * If the tenant already has a config, its current status is included.
 */
public record AvailableGatewayResponse(
        String gatewayType,
        String displayName,
        String description,
        String logoUrl,
        int sortOrder,

        /** Whether the tenant has already created a config for this gateway. */
        boolean configured,

        /** If configured, the config ID (for deep-linking to edit). */
        String configId,

        /** If configured, the current status (DRAFT, TESTED, ERROR, ACTIVE, etc.). */
        String status
) {
}
