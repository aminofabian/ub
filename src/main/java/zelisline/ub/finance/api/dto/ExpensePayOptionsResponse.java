package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;

public record ExpensePayOptionsResponse(
        BigDecimal amount,
        /** Tenant enabled supplier/payout Send Money under Payments settings. */
        boolean payoutEnabled,
        boolean payoutGatewayReady,
        String payoutGatewayLabel,
        boolean destinationConfigured,
        String destinationPhone,
        boolean kopokopoPayEligible,
        boolean pendingDisbursement,
        String pendingDisbursementId,
        String latestDisbursementStatus,
        String latestDisbursementMessage,
        boolean alreadyPaid
) {
}
