package zelisline.ub.payments.api.dto;

import java.time.Instant;

public record PlatformMpesaCustodySettingsResponse(
        String custodyProvider,
        boolean kopokopoReady,
        boolean darajaReady,
        /** True when Daraja STK exists but B2C/B2B disburse is not shipped yet. */
        boolean darajaDisburseAvailable,
        Instant updatedAt
) {
}
