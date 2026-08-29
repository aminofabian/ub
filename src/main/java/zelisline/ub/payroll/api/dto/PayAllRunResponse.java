package zelisline.ub.payroll.api.dto;

import java.util.List;

public record PayAllRunResponse(
        int paidCount,
        int skippedCount,
        List<PayAllRunFailure> failures
) {
    public record PayAllRunFailure(String userId, String displayName, String reason) {
    }
}
