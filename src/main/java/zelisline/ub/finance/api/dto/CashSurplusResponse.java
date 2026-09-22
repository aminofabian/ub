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
        long openShifts,
        boolean destinationConfigured,
        String destinationSummary,
        boolean collidesWithCustomerPay,
        String customerPayCollisionMessage,
        /** Applied jar % (1–100). */
        BigDecimal profitJarPct,
        /** Cash + M-Pesa − float before jar %. */
        BigDecimal rawSurplus
) {
}
