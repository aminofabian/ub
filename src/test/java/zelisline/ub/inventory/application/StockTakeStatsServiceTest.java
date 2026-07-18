package zelisline.ub.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class StockTakeStatsServiceTest {

    @Test
    void currentStreakAllowsMissedTodayIfYesterdayCounted() {
        LocalDate today = LocalDate.of(2026, 7, 18);
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 7, 17),
                LocalDate.of(2026, 7, 16),
                LocalDate.of(2026, 7, 15)
        );
        assertThat(StockTakeStatsService.computeCurrentStreak(dates, today, today))
                .isEqualTo(3);
    }

    @Test
    void bestStreakFindsLongestRun() {
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 7, 10),
                LocalDate.of(2026, 7, 9),
                LocalDate.of(2026, 7, 8),
                LocalDate.of(2026, 7, 5),
                LocalDate.of(2026, 7, 4)
        );
        assertThat(StockTakeStatsService.computeBestStreak(dates)).isEqualTo(3);
    }

    @Test
    void pickTitlePrefersStreakAndCleanRate() {
        assertThat(StockTakeStatsService.pickTitle(10, 50, 80, 7, 0))
                .isEqualTo("Iron shelf");
        assertThat(StockTakeStatsService.pickTitle(25, 50, 96, 2, 0))
                .isEqualTo("Sharp eye");
        assertThat(StockTakeStatsService.pickTitle(0, 0, null, 0, 0))
                .isEqualTo("Ready to count");
    }
}
