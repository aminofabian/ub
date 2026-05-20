package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

public record SupplyPayOptionsResponse(
        BigDecimal balanceOpen,
        /** Tenant enabled supplier payouts under Payments settings. */
        boolean supplierPayoutEnabled,
        /** Selected gateway is active and allowed for supplier payouts. */
        boolean supplierPayoutGatewayReady,
        String supplierPayoutGatewayLabel,
        boolean supplierMobilePayoutConfigured,
        String payoutPhone,
        boolean kopokopoPayEligible,
        boolean pendingDisbursement,
        String pendingDisbursementId
) {
}
