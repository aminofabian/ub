package zelisline.ub.payments.api.dto;

import java.time.Instant;

/**
 * Response DTO for a tenant gateway configuration.
 * API secrets are never returned. {@code displayInstructionsJson} is included for
 * MANUAL / CUSTODY_MPESA (till/paybill destination — not PSP secrets).
 * {@code custodyProvider} is set only for CUSTODY_MPESA — the platform rail that will
 * collect and settle (so the UI can say “Settles via Kiosk · KopoKopo”).
 */
public record GatewayConfigResponse(
        String id,
        String businessId,
        String gatewayType,
        String label,
        String status,
        boolean isDefault,
        Instant lastTestedAt,
        Instant createdAt,
        Instant updatedAt,
        String displayInstructionsJson,
        String custodyProvider
) {
}
