package zelisline.ub.catalog.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

import zelisline.ub.catalog.api.dto.BulkPriceMode;
import zelisline.ub.catalog.api.dto.PriceRounding;

/**
 * Pure price rules for the catalog bulk editor. No persistence.
 * Margin under {@link #LOW_MARGIN_PCT} is thin; selling below buying is a loss.
 */
public final class BulkPriceMath {

    public static final BigDecimal LOW_MARGIN_PCT = new BigDecimal("15");
    public static final int PREVIEW_ROW_LIMIT = 80;
    public static final int MAX_ITEMS = 5_000;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MONEY_SCALE = 2;

    private BulkPriceMath() {
    }

    public record SideOp(BulkPriceMode mode, BigDecimal value, boolean overwriteExisting) {
    }

    public record LineInput(String id, String name, BigDecimal buying, BigDecimal selling) {
    }

    public record LineResult(
            String id,
            String name,
            BigDecimal currentBuying,
            BigDecimal newBuying,
            BigDecimal currentSelling,
            BigDecimal newSelling,
            boolean buyingChanged,
            boolean sellingChanged,
            boolean skippedExisting,
            boolean loss,
            boolean lowMargin
    ) {
        public boolean affected() {
            return buyingChanged || sellingChanged;
        }
    }

    public static void validate(SideOp buying, SideOp selling) {
        if (buying == null && selling == null) {
            throw new IllegalArgumentException("Choose a buying price, a selling price, or both.");
        }
        validateSide(buying, false);
        validateSide(selling, true);
    }

    public static LineResult apply(LineInput input, SideOp buying, SideOp selling, PriceRounding rounding) {
        BigDecimal currentBuying = moneyOrNull(input.buying());
        BigDecimal currentSelling = moneyOrNull(input.selling());
        PriceRounding rule = rounding == null ? PriceRounding.NONE : rounding;

        SideOutcome sellingOutcome = applySide(selling, currentSelling, currentBuying, true, rule);
        BigDecimal nextSelling = sellingOutcome.changed() ? sellingOutcome.value() : currentSelling;
        BigDecimal sellingBasis = sellingOutcome.changed() ? nextSelling : currentSelling;

        SideOutcome buyingOutcome = applySide(buying, currentBuying, sellingBasis, false, rule);
        BigDecimal nextBuying = buyingOutcome.changed() ? buyingOutcome.value() : currentBuying;

        boolean buyingChanged = buyingOutcome.changed() && !sameMoney(nextBuying, currentBuying);
        boolean sellingChanged = sellingOutcome.changed() && !sameMoney(nextSelling, currentSelling);
        boolean skippedExisting = !buyingChanged && !sellingChanged
                && (buyingOutcome.skippedExisting() || sellingOutcome.skippedExisting());

        boolean loss = false;
        boolean lowMargin = false;
        if ((buyingChanged || sellingChanged) && priceSet(nextBuying) && priceSet(nextSelling)) {
            if (nextBuying.compareTo(nextSelling) > 0) {
                loss = true;
            } else {
                BigDecimal margin = nextSelling.subtract(nextBuying)
                        .multiply(HUNDRED)
                        .divide(nextBuying, 4, RoundingMode.HALF_UP);
                lowMargin = margin.compareTo(LOW_MARGIN_PCT) < 0;
            }
        }

        return new LineResult(
                input.id(),
                input.name(),
                currentBuying,
                nextBuying,
                currentSelling,
                nextSelling,
                buyingChanged,
                sellingChanged,
                skippedExisting,
                loss,
                lowMargin);
    }

    public static BigDecimal round(BigDecimal amount, PriceRounding rounding) {
        if (amount == null) {
            return null;
        }
        BigDecimal step = switch (rounding == null ? PriceRounding.NONE : rounding) {
            case NONE -> null;
            case NEAREST_1 -> BigDecimal.ONE;
            case NEAREST_5 -> new BigDecimal("5");
            case NEAREST_10 -> new BigDecimal("10");
        };
        if (step == null) {
            return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return amount.divide(step, 0, RoundingMode.HALF_UP).multiply(step).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static void validateSide(SideOp op, boolean sellingMarkup) {
        if (op == null) {
            return;
        }
        if (op.mode() == null) {
            throw new IllegalArgumentException("Choose how the price should change.");
        }
        BigDecimal value = op.value();
        if (value == null) {
            throw new IllegalArgumentException("Enter an amount or a percent.");
        }
        switch (op.mode()) {
            case SET_AMOUNT -> {
                if (value.signum() <= 0) {
                    throw new IllegalArgumentException("Enter an amount greater than zero.");
                }
            }
            case INCREASE_PERCENT -> {
                if (value.signum() <= 0 || value.compareTo(new BigDecimal("1000")) > 0) {
                    throw new IllegalArgumentException("Increase percent must be between 0 and 1000.");
                }
            }
            case DECREASE_PERCENT -> {
                if (value.signum() <= 0 || value.compareTo(HUNDRED) >= 0) {
                    throw new IllegalArgumentException("Decrease percent must be greater than 0 and less than 100.");
                }
            }
            case PERCENT_OF_COUNTERPART -> {
                if (sellingMarkup) {
                    if (value.signum() < 0 || value.compareTo(new BigDecimal("1000")) > 0) {
                        throw new IllegalArgumentException("Markup percent must be between 0 and 1000.");
                    }
                } else if (value.signum() <= 0 || value.compareTo(new BigDecimal("500")) > 0) {
                    throw new IllegalArgumentException("Percent of selling price must be between 0 and 500.");
                }
            }
        }
    }

    private record SideOutcome(BigDecimal value, boolean changed, boolean skippedExisting) {
        static SideOutcome unchanged() {
            return new SideOutcome(null, false, false);
        }

        static SideOutcome skipped() {
            return new SideOutcome(null, false, true);
        }

        static SideOutcome changed(BigDecimal value) {
            return new SideOutcome(value, true, false);
        }
    }

    private static SideOutcome applySide(
            SideOp op,
            BigDecimal current,
            BigDecimal counterpart,
            boolean sellingMarkup,
            PriceRounding rounding
    ) {
        if (op == null) {
            return SideOutcome.unchanged();
        }
        boolean hasCurrent = priceSet(current);
        if (op.mode() == BulkPriceMode.INCREASE_PERCENT || op.mode() == BulkPriceMode.DECREASE_PERCENT) {
            if (!hasCurrent) {
                return SideOutcome.unchanged();
            }
            BigDecimal factor = op.mode() == BulkPriceMode.INCREASE_PERCENT
                    ? BigDecimal.ONE.add(op.value().divide(HUNDRED, 8, RoundingMode.HALF_UP))
                    : BigDecimal.ONE.subtract(op.value().divide(HUNDRED, 8, RoundingMode.HALF_UP));
            return finish(current.multiply(factor), rounding);
        }
        if (hasCurrent && !op.overwriteExisting()) {
            return SideOutcome.skipped();
        }
        if (op.mode() == BulkPriceMode.SET_AMOUNT) {
            return finish(op.value(), rounding);
        }
        if (!priceSet(counterpart)) {
            return SideOutcome.unchanged();
        }
        BigDecimal raw = sellingMarkup
                ? counterpart.multiply(BigDecimal.ONE.add(op.value().divide(HUNDRED, 8, RoundingMode.HALF_UP)))
                : counterpart.multiply(op.value()).divide(HUNDRED, 8, RoundingMode.HALF_UP);
        return finish(raw, rounding);
    }

    private static SideOutcome finish(BigDecimal raw, PriceRounding rounding) {
        BigDecimal rounded = round(raw, rounding);
        if (!priceSet(rounded)) {
            return SideOutcome.unchanged();
        }
        return SideOutcome.changed(rounded);
    }

    static boolean priceSet(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static boolean sameMoney(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.compareTo(right) == 0;
    }

    private static BigDecimal moneyOrNull(BigDecimal value) {
        if (!priceSet(value)) {
            return null;
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
