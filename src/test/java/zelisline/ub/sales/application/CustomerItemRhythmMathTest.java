package zelisline.ub.sales.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class CustomerItemRhythmMathTest {

    @Test
    void medianGapIsMiddleOfSortedDayGaps() {
        List<LocalDate> days = List.of(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 1, 8),
                LocalDate.of(2026, 1, 22),
                LocalDate.of(2026, 2, 5));
        assertThat(CustomerItemRhythmMath.medianGapDays(days)).isEqualTo(14);
    }

    @Test
    void dueishWhenGapExceedsFactor() {
        assertThat(CustomerItemRhythmMath.dueish(20, 14)).isTrue();
        assertThat(CustomerItemRhythmMath.dueish(14, 14)).isFalse();
        assertThat(CustomerItemRhythmMath.dueish(10, 14)).isFalse();
        assertThat(CustomerItemRhythmMath.dueish(40, null)).isFalse();
    }

    @Test
    void describeUsesShopLanguage() {
        assertThat(CustomerItemRhythmMath.describe(21, 35, true))
                .isEqualTo("Usually every 3 weeks — last bought 5 weeks ago");
        assertThat(CustomerItemRhythmMath.describe(7, 8, false))
                .contains("Usually every 1 week");
    }
}
