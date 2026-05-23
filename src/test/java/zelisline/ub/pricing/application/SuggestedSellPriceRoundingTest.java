package zelisline.ub.pricing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class SuggestedSellPriceRoundingTest {

    @Test
    void roundsUnder100ToNearestFive() {
        assertThat(SuggestedSellPriceRounding.round(new BigDecimal("13.75")))
                .isEqualByComparingTo("15");
        assertThat(SuggestedSellPriceRounding.round(new BigDecimal("12.40")))
                .isEqualByComparingTo("10");
        assertThat(SuggestedSellPriceRounding.round(new BigDecimal("99.20")))
                .isEqualByComparingTo("100");
    }

    @Test
    void roundsAtOrAbove100ToNearestTen() {
        assertThat(SuggestedSellPriceRounding.round(new BigDecimal("153.75")))
                .isEqualByComparingTo("150");
        assertThat(SuggestedSellPriceRounding.round(new BigDecimal("156.20")))
                .isEqualByComparingTo("160");
        assertThat(SuggestedSellPriceRounding.round(new BigDecimal("100.00")))
                .isEqualByComparingTo("100");
    }

    @Test
    void leavesAlreadyAlignedValuesUnchanged() {
        assertThat(SuggestedSellPriceRounding.round(new BigDecimal("10.00")))
                .isEqualByComparingTo("10");
        assertThat(SuggestedSellPriceRounding.round(new BigDecimal("125.00")))
                .isEqualByComparingTo("125");
    }
}
