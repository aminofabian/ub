package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CashSurplusResponse(
        LocalDate from,
        LocalDate to,
        String branchId,
        BigDecimal cashTakings,
        BigDecimal mpesaTakings,
        BigDecimal creditTakings,
        BigDecimal defaultFloat,
        BigDecimal suggestedPocket,
        BigDecimal grossProfit,
        /** Net operating profit (gross − operating expenses). The pocket suggestion is built from this, never gross. */
        BigDecimal netProfit,
        long openShifts,
        boolean destinationConfigured,
        String destinationSummary,
        boolean collidesWithCustomerPay,
        String customerPayCollisionMessage,
        /** Applied jar % (1–100). */
        BigDecimal profitJarPct,
        /** Cash + M-Pesa − float (liquidity cap). Suggested pocket uses min(net profit, this) × jar %. */
        BigDecimal rawSurplus,
        /** Owner drawings already posted for this window. */
        BigDecimal alreadyPocketed,
        /** Net profit still not pocketed. Zero when profit is gone or already taken. */
        BigDecimal profitBalance
) {
}
