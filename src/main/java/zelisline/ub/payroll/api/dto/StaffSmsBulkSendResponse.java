package zelisline.ub.payroll.api.dto;

import java.util.List;

public record StaffSmsBulkSendResponse(
        int sent,
        int skipped,
        List<StaffSmsBulkFailure> failures
) {
    public record StaffSmsBulkFailure(String userId, String displayName, String reason) {
    }
}
