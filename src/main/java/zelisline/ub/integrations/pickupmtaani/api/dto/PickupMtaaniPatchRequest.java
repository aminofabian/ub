package zelisline.ub.integrations.pickupmtaani.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Write-only patch for the per-tenant Pickup Mtaani configuration
 * ({@code businesses.settings.pickupMtaani}).
 *
 * <p>Semantics: {@code null} leaves a value unchanged. A non-blank {@code apiKey}
 * triggers a connect (verify upstream and bind {@code businessId}); it is
 * encrypted at rest and never returned. {@code originAgentName} and
 * {@code originLocationName} are display labels the client already has from a
 * search — they are stored so the order pages do not need a live lookup.
 */
public record PickupMtaaniPatchRequest(
        Boolean enabled,
        /** Write-only tenant API key. Blank or null leaves the stored key unchanged. */
        @Size(max = 512)
        String apiKey,
        /** {@code pass_through} | {@code absorb} | {@code markup}; null leaves unchanged. */
        @Size(max = 24)
        String feeMode,
        /** Fixed KES markup, required when {@code feeMode=markup}. */
        @PositiveOrZero
        @Max(1_000_000)
        Integer markupKes,
        Boolean agent,
        Boolean doorstep,
        Boolean bookOnDispatch,
        Long originAgentId,
        @Size(max = 160)
        String originAgentName,
        @Size(max = 160)
        String originLocationName
) {
}
