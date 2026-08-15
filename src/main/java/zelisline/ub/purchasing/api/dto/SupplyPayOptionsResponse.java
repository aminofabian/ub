package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

public record SupplyPayOptionsResponse(
        BigDecimal balanceOpen,
        /** Tenant enabled supplier payouts under Payments settings. */
        boolean supplierPayoutEnabled,
        /** Selected gateway is active and allowed for supplier payouts. */
        boolean supplierPayoutGatewayReady,
        String supplierPayoutGatewayLabel,
        /**
         * Supplier has an automated KopoKopo destination configured
         * (mobile_wallet, till, or paybill).
         */
        boolean supplierMobilePayoutConfigured,
        String payoutType,
        String payoutPhone,
        String payoutTillNumber,
        String payoutPaybillNumber,
        String payoutPaybillAccount,
        boolean kopokopoPayEligible,
        boolean pendingDisbursement,
        String pendingDisbursementId,
        /** Latest Send Money row: pending, failed, cancelled, success, or null. */
        String latestDisbursementStatus,
        String latestDisbursementMessage
) {
}
