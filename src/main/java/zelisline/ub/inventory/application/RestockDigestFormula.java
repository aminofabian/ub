package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import zelisline.ub.inventory.InventoryConstants;

/**
 * Pure restock suggestion math — no JPA / Spring dependencies so the formula edge
 * cases (pack rounding, inbound subtraction, stock-out recovery, dead stock,
 * snoozed skip) are unit-testable in isolation. All quantities are in sale /
 * display units (matches grocery UI + {@code PackageVariantStockResolver}).
 */
final class RestockDigestFormula {

    private static final int QTY_SCALE = 4;

    private RestockDigestFormula() {
    }

    record VelocityInput(
            BigDecimal last7Qty,
            BigDecimal last30Qty,
            long daysWithSales
    ) {
    }

    record LinkInput(
            BigDecimal packSize,
            BigDecimal minOrderQty,
            Integer leadTimeDays
    ) {
    }

    record Computed(
            BigDecimal par,
            BigDecimal suggestedQty,
            String reasonCode,
            String evidence,
            String confidence
    ) {
    }

    /**
     * Compute a suggestion for one candidate.
     *
     * @param snoozed  true → always empty (suppression)
     * @param onHand   branch display on-hand
     * @param inbound  open PO + order-pad qty already decided
     * @param stockOut true when the item was counted zero / on-hand is zero (STOCKOUT_RECOVERY input)
     */
    static Optional<Computed> compute(
            BigDecimal onHand,
            BigDecimal inbound,
            BigDecimal reorderLevel,
            BigDecimal parManual,
            VelocityInput velocity,
            LinkInput link,
            int coverDays,
            boolean stockOut,
            boolean snoozed
    ) {
        if (snoozed) {
            return Optional.empty();
        }
        BigDecimal effective = add(onHand, inbound);
        BigDecimal avgDaily = avgDaily(velocity);
        int leadTime = link != null && link.leadTimeDays() != null && link.leadTimeDays() > 0
                ? link.leadTimeDays()
                : 1;

        boolean thinHistory = velocity == null || velocity.daysWithSales() < 7;
        BigDecimal par;
        String confidence;
        if (thinHistory) {
            par = fallbackPar(parManual, reorderLevel);
            confidence = InventoryConstants.DIGEST_CONFIDENCE_LOW;
        } else {
            BigDecimal derived = avgDaily
                    .multiply(BigDecimal.valueOf((long) coverDays + leadTime))
                    .setScale(0, RoundingMode.CEILING);
            par = parManual != null ? parManual : derived;
            confidence = velocity.daysWithSales() >= 14
                    ? InventoryConstants.DIGEST_CONFIDENCE_HIGH
                    : InventoryConstants.DIGEST_CONFIDENCE_MEDIUM;
        }

        if (par == null) {
            // No threshold and no velocity → cannot compute (suppression).
            return Optional.empty();
        }

        BigDecimal suggested = par.subtract(effective).max(BigDecimal.ZERO);
        BigDecimal pack = link != null && link.packSize() != null && link.packSize().signum() > 0
                ? link.packSize()
                : null;
        if (suggested.signum() > 0 && pack != null) {
            suggested = roundUpToPack(suggested, pack);
        }
        if (suggested.signum() > 0
                && link != null && link.minOrderQty() != null && link.minOrderQty().signum() > 0) {
            suggested = suggested.max(link.minOrderQty());
        }

        Set<String> reasons = reasons(
                onHand,
                effective,
                reorderLevel,
                par,
                avgDaily,
                leadTime,
                stockOut,
                suggested);

        // Inclusion: line appears only when suggested > 0 AND at least one reason matches.
        if (suggested.signum() <= 0 || reasons.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new Computed(
                par,
                suggested,
                String.join("+", reasons),
                evidence(reasons, avgDaily, effective, reorderLevel, thinHistory),
                confidence));
    }

    private static BigDecimal avgDaily(VelocityInput velocity) {
        if (velocity == null || velocity.last30Qty() == null || velocity.last30Qty().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal last7Qty = velocity.last7Qty() == null ? BigDecimal.ZERO : velocity.last7Qty();
        BigDecimal last7 = last7Qty.divide(BigDecimal.valueOf(7), QTY_SCALE, RoundingMode.HALF_UP);
        BigDecimal last23Qty = velocity.last30Qty().subtract(last7Qty).max(BigDecimal.ZERO);
        BigDecimal last23 = last23Qty.divide(BigDecimal.valueOf(23), QTY_SCALE, RoundingMode.HALF_UP);
        return last7.multiply(new BigDecimal("0.7"))
                .add(last23.multiply(new BigDecimal("0.3")))
                .setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal fallbackPar(BigDecimal parManual, BigDecimal reorderLevel) {
        if (parManual != null) {
            return parManual;
        }
        if (reorderLevel != null) {
            return reorderLevel.multiply(BigDecimal.valueOf(2)).max(reorderLevel);
        }
        return null;
    }

    private static Set<String> reasons(
            BigDecimal onHand,
            BigDecimal effective,
            BigDecimal reorderLevel,
            BigDecimal par,
            BigDecimal avgDaily,
            int leadTime,
            boolean stockOut,
            BigDecimal suggested
    ) {
        Set<String> out = new LinkedHashSet<>();
        if (reorderLevel != null && onHand.compareTo(reorderLevel) <= 0) {
            out.add(InventoryConstants.DIGEST_REASON_BELOW_MIN);
        }
        BigDecimal runoutThreshold = avgDaily.multiply(BigDecimal.valueOf(leadTime));
        if (runoutThreshold.signum() > 0 && effective.compareTo(runoutThreshold) < 0) {
            out.add(InventoryConstants.DIGEST_REASON_WILL_STOCK_OUT);
        }
        if (suggested.signum() > 0
                && effective.compareTo(par) < 0
                && reorderLevel != null
                && effective.subtract(runoutThreshold).compareTo(reorderLevel) < 0) {
            out.add(InventoryConstants.DIGEST_REASON_FAST_MOVER);
        }
        if (stockOut && effective.compareTo(par) < 0) {
            out.add(InventoryConstants.DIGEST_REASON_STOCKOUT_RECOVERY);
        }
        return out;
    }

    private static String evidence(
            Set<String> reasons,
            BigDecimal avgDaily,
            BigDecimal effective,
            BigDecimal reorderLevel,
            boolean thinHistory
    ) {
        if (thinHistory || avgDaily.signum() <= 0) {
            return "No sales history · order up to min×2";
        }
        BigDecimal daysLeft = effective.divide(avgDaily, 1, RoundingMode.HALF_UP);
        StringBuilder sb = new StringBuilder("Sold ")
                .append(avgDaily.setScale(1, RoundingMode.HALF_UP))
                .append("/day · ")
                .append(daysLeft)
                .append(" days left");
        if (reorderLevel != null
                && reasons.contains(InventoryConstants.DIGEST_REASON_BELOW_MIN)) {
            sb.append(" · below min ").append(reorderLevel.stripTrailingZeros().toPlainString());
        }
        return sb.toString();
    }

    static BigDecimal roundUpToPack(BigDecimal qty, BigDecimal packSize) {
        if (qty == null || qty.signum() <= 0 || packSize == null || packSize.signum() <= 0) {
            return qty == null ? BigDecimal.ZERO : qty;
        }
        BigDecimal packs = qty.divide(packSize, 0, RoundingMode.CEILING);
        return packs.multiply(packSize).setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        BigDecimal x = a == null ? BigDecimal.ZERO : a;
        BigDecimal y = b == null ? BigDecimal.ZERO : b;
        return x.add(y).setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }
}
