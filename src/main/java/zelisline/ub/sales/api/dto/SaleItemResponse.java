package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

public record SaleItemResponse(
        String id,
        int lineIndex,
        String itemId,
        String batchId,
        String lineKind,
        String lineLabel,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        BigDecimal unitCost,
        BigDecimal costTotal,
        BigDecimal profit,
        /** KRA eTIMS tax code (A/B/C/D/E) captured at sale time; null when untaxed. */
        String taxCode,
        BigDecimal taxRatePercent,
        /** Tax contained in {@link #lineTotal} (prices are tax-inclusive). */
        BigDecimal taxAmount
) {
}
