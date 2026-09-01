package zelisline.ub.credits.api.dto;

import java.util.List;

public record CustomerBulkMessageResponse(
        int sent,
        int skipped,
        List<CustomerBulkMessageFailure> failures
) {
    public record CustomerBulkMessageFailure(
            String customerId,
            String customerName,
            String reason
    ) {
    }
}
