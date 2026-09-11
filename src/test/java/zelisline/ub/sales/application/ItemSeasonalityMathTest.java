package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class ItemSeasonalityMathTest {

    @Test
    void peakMonthsAreWithinEightyPercentOfMax() {
        List<BigDecimal> months = List.of(
                bd(10), bd(12), bd(80), bd(100), bd(90), bd(20),
                bd(8), bd(15), bd(10), bd(12), bd(18), bd(95));
        assertThat(ItemSeasonalityMath.peakMonths(months)).containsExactly(3, 4, 5, 12);
    }

    @Test
    void festiveAndAugustHints() {
        assertThat(ItemSeasonalityMath.kenyaSeasonHint(12)).isEqualTo("Festive");
        assertThat(ItemSeasonalityMath.kenyaSeasonHint(1)).isEqualTo("Festive");
        assertThat(ItemSeasonalityMath.kenyaSeasonHint(8)).isEqualTo("August holiday");
        assertThat(ItemSeasonalityMath.kenyaSeasonHint(4)).isEqualTo("Easter");
        assertThat(ItemSeasonalityMath.monthLabel(12)).contains("Festive");
        assertThat(ItemSeasonalityMath.monthLabel(6)).isEqualTo("Jun");
    }

    private static BigDecimal bd(int n) {
        return BigDecimal.valueOf(n);
    }
}
