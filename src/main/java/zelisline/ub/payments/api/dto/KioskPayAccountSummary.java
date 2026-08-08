package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;

/** Super-admin aggregate of all tenant Kiosk Pay accounts (platform float). */
public record KioskPayAccountSummary(
        long accountCount,
        BigDecimal totalAvailable,
        BigDecimal totalPending,
        BigDecimal totalLifetimeIn,
        BigDecimal totalLifetimeOut
) {
}
