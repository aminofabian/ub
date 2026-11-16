package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

/**
 * One bottle on the bar usage report (ADR 0024): where its stock went over the window and what the
 * count should show. All quantities are in the bottle's own stock unit.
 *
 * @param itemId       the bottle (a stocked product)
 * @param name         its name
 * @param sku          its SKU, if any
 * @param openingOnHand on-hand at the window's start, worked back from now and the window's movements
 * @param poured       how much left as pour measures (net of voided pours) — positive is the amount gone
 * @param directSold   how much sold as whole units (net of voids and refunds)
 * @param wasted       how much written off as wastage (over-pour, spillage, breakage)
 * @param otherMoved   any other net movement in the window (deliveries in, transfers, adjustments); signed
 * @param systemOnHand what the books say is left now (already reflects every pour)
 * @param counted      the physical count, when one has been entered; null otherwise
 * @param variance     counted − systemOnHand when counted is present; null otherwise. Negative = short
 *                     (over-pour / spillage / shrinkage); positive = more than expected.
 */
public record BarUsageLine(
        String itemId,
        String name,
        String sku,
        BigDecimal openingOnHand,
        BigDecimal poured,
        BigDecimal directSold,
        BigDecimal wasted,
        BigDecimal otherMoved,
        BigDecimal systemOnHand,
        BigDecimal counted,
        BigDecimal variance
) {
}
