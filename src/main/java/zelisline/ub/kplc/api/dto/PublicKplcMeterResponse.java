package zelisline.ub.kplc.api.dto;

import java.time.Instant;

public record PublicKplcMeterResponse(
        String meterNumber,
        Instant lastUsedAt
) {
}
