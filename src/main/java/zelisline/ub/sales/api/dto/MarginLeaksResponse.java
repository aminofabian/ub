package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Gross-profit reconciliation for the Hub “Why negative?” drawer.
 *
 * <p>The drawer’s item list only covers catalog items that still exist. The card also counts
 * airtime lines and lines whose product was later removed, and both sides treat refunds
 * differently — so the list can never be read off the card directly. This response carries the
 * components so the two always reconcile:
 *
 * <pre>
 * grossProfit = listedProfit + refundsInWindow + removedItemsProfit + airtimeProfit
 * </pre>
 */
public record MarginLeaksResponse(
        LocalDate from,
        LocalDate to,
        String branchId,
        /** Σ sale-line profit for sales in the window — the same basis the Hub card uses. */
        BigDecimal grossProfit,
        /** Σ net profit across every ranked item (what the item list sums to). */
        BigDecimal listedProfit,
        /** Profit reversed by refunds in the window. */
        BigDecimal refundsInWindow,
        /** Profit from lines whose product was deleted/removed after the sale. */
        BigDecimal removedItemsProfit,
        /** Profit from airtime lines (no catalog item). */
        BigDecimal airtimeProfit,
        List<MarginLeakRow> rows
) {
}
