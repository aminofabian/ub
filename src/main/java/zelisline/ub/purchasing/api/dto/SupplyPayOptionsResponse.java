package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

public record SupplyPayOptionsResponse(
        BigDecimal balanceOpen,
        boolean kopokopoActive,
        boolean supplierMobilePayoutConfigured,
        String payoutPhone,
        boolean kopokopoPayEligible,
        boolean pendingDisbursement,
        String pendingDisbursementId
) {
}
