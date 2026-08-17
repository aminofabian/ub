package zelisline.ub.kplc.api.dto;

import java.time.Instant;
import java.util.List;

public record PublicKplcConfigResponse(
        boolean purchaseAvailable,
        String purchaseMessage,
        List<PublicKplcMeterResponse> meters
) {
}
