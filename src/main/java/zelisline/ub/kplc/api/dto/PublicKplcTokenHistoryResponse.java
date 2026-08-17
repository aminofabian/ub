package zelisline.ub.kplc.api.dto;

import java.util.List;

public record PublicKplcTokenHistoryResponse(
        String meterNumber,
        boolean purchaseAvailable,
        String purchaseMessage,
        List<PublicKplcTokenResponse> tokens,
        PublicKplcSpendStatsResponse stats,
        PublicKplcDepletionResponse depletion
) {
}
