package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Splits a sale's gross amount between net revenue and the tax contained in it,
 * so the ledger can credit {@code Sales revenue} net and a {@code VAT payable}
 * liability, rather than putting the whole gross figure through revenue.
 *
 * <p>Shared by the sale, void, and refund journal builders so all three agree on
 * the split and the entry always balances. A sale with no tax yields a zero tax
 * component, so untaxed shops post exactly as before.
 */
public final class SaleTaxLedgerSplit {

    private static final int MONEY_SCALE = 2;

    private SaleTaxLedgerSplit() {
    }

    public static Split of(BigDecimal gross, BigDecimal taxTotal) {
        BigDecimal grossScaled = scale(gross);
        BigDecimal tax = scale(taxTotal);
        if (tax.signum() < 0) {
            tax = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        // Tax can never exceed the amount it is contained in; clamp so net stays non-negative.
        if (tax.compareTo(grossScaled) > 0) {
            tax = grossScaled;
        }
        BigDecimal net = grossScaled.subtract(tax).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new Split(net, tax);
    }

    private static BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    public record Split(BigDecimal net, BigDecimal tax) {
    }
}
