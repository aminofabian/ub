package zelisline.ub.sales.api.dto;

import java.time.Instant;

/** The drawer switch closed, or confirmed a pulse actually opened it. */
public record PatchCashDrawerOpenRequest(
        Instant closedAt,
        Boolean sensorConfirmed
) {
}
