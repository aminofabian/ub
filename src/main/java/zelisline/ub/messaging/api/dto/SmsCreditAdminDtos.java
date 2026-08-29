package zelisline.ub.messaging.api.dto;

import java.util.List;

/** Super Admin per-business SMS credit management DTOs (§10, §11). */
public record SmsCreditAdminDtos() {

    public record GrantRequest(
            Integer credits,
            String note
    ) {
    }

    public record UpdateAccountRequest(
            Integer includedOverride
    ) {
    }

    public record AccountResponse(
            String businessId,
            int includedUsed,
            Integer includedOverride,
            int includedAllowance,
            int includedRemaining,
            int purchasedBalance,
            int available,
            String cycleStartedAt,
            List<SmsCreditLedgerResponse.SmsCreditLedgerRow> recentLedger,
            List<SmsCreditPurchaseDtos.SmsCreditPurchaseResponse> recentPurchases
    ) {
    }
}
