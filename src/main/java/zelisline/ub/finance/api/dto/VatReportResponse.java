package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * VAT position for a period — what a shop needs to fill its KRA return.
 *
 * <p>The money figures come from the ledger's VAT payable account, so they already
 * net out voids and refunds. {@code byTaxCode} is the sales mix from completed sales
 * in the period (gross of refund adjustments) — useful for the return's class
 * breakdown, but not a reversal-adjusted figure.
 */
public record VatReportResponse(
        LocalDate from,
        LocalDate to,
        String branchId,
        /** VAT charged on sales in the period. */
        BigDecimal vatCollected,
        /** VAT reversed by voids and refunds in the period. */
        BigDecimal vatReversed,
        /** Net VAT due for the period (collected − reversed). */
        BigDecimal vatPayable,
        /** Sales revenue recognised in the period, net of VAT. */
        BigDecimal netRevenue,
        /** Sales mix by KRA tax code (completed sales in the period). */
        List<TaxCodeRow> byTaxCode
) {

    public record TaxCodeRow(
            /** KRA tax code (A/B/C/D/E); empty when the line carried no tax rate. */
            String taxCode,
            BigDecimal taxableAmount,
            BigDecimal taxAmount,
            long lineCount
    ) {
    }
}
