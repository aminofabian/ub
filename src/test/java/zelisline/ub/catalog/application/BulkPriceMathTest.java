package zelisline.ub.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import zelisline.ub.catalog.api.dto.BulkPriceMode;
import zelisline.ub.catalog.api.dto.PriceRounding;

class BulkPriceMathTest {

    @Test
    void buyingAtEightyPercentOfSelling() {
        BulkPriceMath.LineResult result = BulkPriceMath.apply(
                line("Sugar 1kg", null, "100"),
                side(BulkPriceMode.PERCENT_OF_COUNTERPART, "80", false),
                null,
                PriceRounding.NONE);
        assertThat(result.newBuying()).isEqualByComparingTo("80.00");
        assertThat(result.newSelling()).isEqualByComparingTo("100.00");
        assertThat(result.buyingChanged()).isTrue();
        assertThat(result.sellingChanged()).isFalse();
        assertThat(result.loss()).isFalse();
        assertThat(result.lowMargin()).isFalse();
    }

    @Test
    void sellingMarkupOfTwentyFivePercent() {
        BulkPriceMath.LineResult result = BulkPriceMath.apply(
                line("Bread", "80", null),
                null,
                side(BulkPriceMode.PERCENT_OF_COUNTERPART, "25", false),
                PriceRounding.NONE);
        assertThat(result.newSelling()).isEqualByComparingTo("100.00");
        assertThat(result.newBuying()).isEqualByComparingTo("80.00");
        assertThat(result.sellingChanged()).isTrue();
    }

    @Test
    void doesNotOverwriteAnExistingPriceUnlessAsked() {
        BulkPriceMath.LineResult result = BulkPriceMath.apply(
                line("Milk", "50", "100"),
                side(BulkPriceMode.PERCENT_OF_COUNTERPART, "80", false),
                null,
                PriceRounding.NONE);
        assertThat(result.buyingChanged()).isFalse();
        assertThat(result.skippedExisting()).isTrue();
        assertThat(result.newBuying()).isEqualByComparingTo("50.00");
    }

    @Test
    void overwriteReplacesTheExistingPrice() {
        BulkPriceMath.LineResult result = BulkPriceMath.apply(
                line("Milk", "50", "100"),
                side(BulkPriceMode.SET_AMOUNT, "70", true),
                null,
                PriceRounding.NONE);
        assertThat(result.newBuying()).isEqualByComparingTo("70.00");
        assertThat(result.buyingChanged()).isTrue();
        assertThat(result.skippedExisting()).isFalse();
    }

    @Test
    void increaseAndDecreasePercentsNeedACurrentPrice() {
        BulkPriceMath.LineResult raised = BulkPriceMath.apply(
                line("Rice", "200", "250"),
                side(BulkPriceMode.INCREASE_PERCENT, "10", false),
                side(BulkPriceMode.DECREASE_PERCENT, "10", false),
                PriceRounding.NONE);
        assertThat(raised.newBuying()).isEqualByComparingTo("220.00");
        assertThat(raised.newSelling()).isEqualByComparingTo("225.00");

        BulkPriceMath.LineResult missing = BulkPriceMath.apply(
                line("Salt", null, "40"),
                side(BulkPriceMode.INCREASE_PERCENT, "10", true),
                null,
                PriceRounding.NONE);
        assertThat(missing.buyingChanged()).isFalse();
        assertThat(missing.skippedExisting()).isFalse();
    }

    @Test
    void roundsToNearestFiveAndTen() {
        assertThat(BulkPriceMath.round(new BigDecimal("82"), PriceRounding.NEAREST_5))
                .isEqualByComparingTo("80.00");
        assertThat(BulkPriceMath.round(new BigDecimal("83"), PriceRounding.NEAREST_5))
                .isEqualByComparingTo("85.00");
        assertThat(BulkPriceMath.round(new BigDecimal("84"), PriceRounding.NEAREST_10))
                .isEqualByComparingTo("80.00");
        assertThat(BulkPriceMath.round(new BigDecimal("85"), PriceRounding.NEAREST_10))
                .isEqualByComparingTo("90.00");
    }

    @Test
    void flagsLossAndThinMargin() {
        BulkPriceMath.LineResult loss = BulkPriceMath.apply(
                line("Loss", null, "100"),
                side(BulkPriceMode.SET_AMOUNT, "120", false),
                null,
                PriceRounding.NONE);
        assertThat(loss.loss()).isTrue();
        assertThat(loss.lowMargin()).isFalse();

        BulkPriceMath.LineResult thin = BulkPriceMath.apply(
                line("Thin", null, "100"),
                side(BulkPriceMode.SET_AMOUNT, "90", false),
                null,
                PriceRounding.NONE);
        assertThat(thin.loss()).isFalse();
        assertThat(thin.lowMargin()).isTrue();
    }

    @Test
    void sellingIsComputedBeforeBuyingUsesIt() {
        BulkPriceMath.LineResult result = BulkPriceMath.apply(
                line("Combo", "40", null),
                side(BulkPriceMode.PERCENT_OF_COUNTERPART, "90", true),
                side(BulkPriceMode.PERCENT_OF_COUNTERPART, "25", false),
                PriceRounding.NONE);
        assertThat(result.newSelling()).isEqualByComparingTo("50.00");
        assertThat(result.newBuying()).isEqualByComparingTo("45.00");
        assertThat(result.buyingChanged()).isTrue();
        assertThat(result.sellingChanged()).isTrue();
    }

    @Test
    void rejectsABlankOperation() {
        assertThatThrownBy(() -> BulkPriceMath.validate(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BulkPriceMath.LineInput line(String name, String buying, String selling) {
        return new BulkPriceMath.LineInput(
                "id",
                name,
                buying == null ? null : new BigDecimal(buying),
                selling == null ? null : new BigDecimal(selling));
    }

    private static BulkPriceMath.SideOp side(BulkPriceMode mode, String value, boolean overwrite) {
        return new BulkPriceMath.SideOp(mode, new BigDecimal(value), overwrite);
    }
}
