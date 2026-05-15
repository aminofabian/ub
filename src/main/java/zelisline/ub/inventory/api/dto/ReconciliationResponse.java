package zelisline.ub.inventory.api.dto;

import java.util.List;

public record ReconciliationResponse(
        String morningSessionId,
        String eveningSessionId,
        String morningSessionName,
        String eveningSessionName,
        int totalReconciled,
        int zeroVariance,
        int withVariance,
        int morningConfirmedCount,
        int eveningConfirmedCount,
        int missingInMorningCount,
        int missingInEveningCount,
        List<ReconciliationLine> lines
) {
}
