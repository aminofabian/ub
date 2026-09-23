package zelisline.ub.finance.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.finance.application.ProfitPocketCalendarMath.DayDraft;
import zelisline.ub.finance.application.ProfitPocketCalendarMath.DayInput;
import zelisline.ub.finance.application.ProfitPocketCalendarMath.Insight;
import zelisline.ub.finance.application.ProfitPocketCalendarMath.LogDraft;
import zelisline.ub.finance.application.ProfitPocketCalendarMath.MonthSummary;

class ProfitPocketCalendarMathTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 23);

    @Test
    void greenerAsMoreOfTheProfitIsPocketed() {
        assertThat(status(TODAY.minusDays(1), "8000", 1, "8000", null)).isEqualTo("full");
        assertThat(status(TODAY.minusDays(1), "8000", 1, "6400", null)).isEqualTo("high");
        assertThat(status(TODAY.minusDays(1), "8000", 1, "5000", null)).isEqualTo("medium");
        assertThat(status(TODAY.minusDays(1), "8000", 1, "1000", null)).isEqualTo("low");
    }

    @Test
    void pastProfitWithoutALogIsMissedAndTodayStaysOpen() {
        assertThat(status(TODAY.minusDays(1), "8000", 2, null, null)).isEqualTo("missed");
        assertThat(status(TODAY, "8000", 2, null, null)).isEqualTo("unreviewed");
    }

    @Test
    void closedAndLossDaysAreNotMissed() {
        assertThat(status(TODAY.minusDays(2), "0", 0, null, null)).isEqualTo("no_profit");
        assertThat(status(TODAY.minusDays(2), "-400", 3, null, null)).isEqualTo("loss");
        assertThat(status(TODAY.plusDays(1), "8000", 1, null, null)).isEqualTo("future");
    }

    @Test
    void skippedDayIsNotMissed() {
        DayDraft day = ProfitPocketCalendarMath.buildDay(
                new DayInput(
                        TODAY.minusDays(3),
                        new BigDecimal("8000"),
                        4,
                        new LogDraft(BigDecimal.ZERO, new BigDecimal("8000"), "skipped", null, "Left it for stock", "manual", false),
                        List.of()),
                TODAY);
        assertThat(day.status()).isEqualTo("skipped");
        assertThat(day.skipReason()).isEqualTo("Left it for stock");
    }

    @Test
    void snapshotStaysWhenLiveProfitMoves() {
        DayDraft day = ProfitPocketCalendarMath.buildDay(
                new DayInput(
                        TODAY.minusDays(1),
                        new BigDecimal("9000"),
                        2,
                        new LogDraft(new BigDecimal("5000"), new BigDecimal("8000"), "recorded", "Took money home", null, "manual", false),
                        List.of()),
                TODAY);
        assertThat(day.pocketingPercentage()).isEqualByComparingTo("62.5");
        assertThat(day.remainingProfit()).isEqualByComparingTo("3000.00");
        assertThat(day.profitMoved()).isTrue();
        assertThat(day.profitAmount()).isEqualByComparingTo("8000.00");
    }

    @Test
    void weekPocketRemainderLandsOnTheLastDay() {
        LocalDate from = LocalDate.of(2026, 9, 1);
        LocalDate to = LocalDate.of(2026, 9, 3);
        assertThat(ProfitPocketCalendarMath.allocate(new BigDecimal("100.00"), from, to, from))
                .isEqualByComparingTo("33.33");
        assertThat(ProfitPocketCalendarMath.allocate(new BigDecimal("100.00"), from, to, from.plusDays(1)))
                .isEqualByComparingTo("33.33");
        assertThat(ProfitPocketCalendarMath.allocate(new BigDecimal("100.00"), from, to, to))
                .isEqualByComparingTo("33.34");
    }

    @Test
    void streakSkipsClosedDaysAndStopsOnAMiss() {
        List<DayDraft> days = new ArrayList<>();
        days.add(day(LocalDate.of(2026, 9, 19), "full", "1000"));
        days.add(day(LocalDate.of(2026, 9, 20), "no_profit", "0"));
        days.add(day(LocalDate.of(2026, 9, 21), "low", "200"));
        days.add(day(LocalDate.of(2026, 9, 22), "missed", "0"));
        days.add(day(LocalDate.of(2026, 9, 23), "unreviewed", "0"));
        assertThat(ProfitPocketCalendarMath.currentStreak(days, TODAY)).isZero();
        assertThat(ProfitPocketCalendarMath.longestStreak(days)).isEqualTo(2);

        List<DayDraft> open = List.of(
                day(LocalDate.of(2026, 9, 21), "full", "1000"),
                day(LocalDate.of(2026, 9, 22), "no_profit", "0"),
                day(LocalDate.of(2026, 9, 23), "unreviewed", "0"));
        assertThat(ProfitPocketCalendarMath.currentStreak(open, TODAY)).isEqualTo(1);
    }

    @Test
    void rateAndAmountAreSeparateObservations() {
        MonthSummary august = summary("2026-08", "100000", "60000", 10);
        MonthSummary september = summary("2026-09", "200000", "100000", 10);
        List<Insight> insights = ProfitPocketCalendarMath.insights(september, august);
        assertThat(insights).extracting(Insight::code).contains("amount_up", "profit_up_rate_down", "rate_down");
        assertThat(insights).extracting(Insight::code).doesNotContain("rate_up");
    }

    private static String status(LocalDate date, String profit, long sales, String pocketed, LogDraft log) {
        LogDraft resolved = pocketed == null
                ? log
                : new LogDraft(new BigDecimal(pocketed), new BigDecimal(profit), "recorded", null, null, "manual", false);
        return ProfitPocketCalendarMath.buildDay(
                new DayInput(date, new BigDecimal(profit), sales, resolved, List.of()),
                TODAY).status();
    }

    private static DayDraft day(LocalDate date, String status, String pocketed) {
        BigDecimal amount = new BigDecimal(pocketed);
        BigDecimal profit = amount.signum() > 0 ? amount : new BigDecimal("1000");
        if ("no_profit".equals(status)) {
            profit = BigDecimal.ZERO;
        }
        return ProfitPocketCalendarMath.buildDay(
                new DayInput(
                        date,
                        profit,
                        "no_profit".equals(status) ? 0 : 1,
                        "missed".equals(status) || "unreviewed".equals(status) || "no_profit".equals(status)
                                ? null
                                : new LogDraft(amount, profit, "recorded", null, null, "manual", false),
                        List.of()),
                TODAY);
    }

    private static MonthSummary summary(String month, String profit, String pocketed, int days) {
        YearMonth ym = YearMonth.parse(month);
        List<DayDraft> rows = new ArrayList<>();
        BigDecimal eachProfit = new BigDecimal(profit).divide(BigDecimal.valueOf(days));
        BigDecimal eachPocket = new BigDecimal(pocketed).divide(BigDecimal.valueOf(days));
        for (int i = 0; i < days; i++) {
            LocalDate date = ym.atDay(i + 1);
            rows.add(ProfitPocketCalendarMath.buildDay(
                    new DayInput(
                            date,
                            eachProfit,
                            1,
                            new LogDraft(eachPocket, eachProfit, "recorded", null, null, "manual", false),
                            List.of()),
                    TODAY));
        }
        return ProfitPocketCalendarMath.summarize(ym, rows);
    }
}
