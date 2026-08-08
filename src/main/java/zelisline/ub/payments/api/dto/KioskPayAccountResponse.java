package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record KioskPayAccountResponse(
        String id,
        String businessId,
        String status,
        String payoutPhone,
        BigDecimal availableBalance,
        BigDecimal pendingBalance,
        BigDecimal lifetimeIn,
        BigDecimal lifetimeOut,
        BigDecimal feePercent,
        BigDecimal platformFeePercent,
        boolean storefrontEnabled,
        boolean platformEnabled,
        BigDecimal minWithdrawAmount,
        BigDecimal dailyWithdrawLimit,
        Instant updatedAt
) {
}
