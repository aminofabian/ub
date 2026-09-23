package zelisline.ub.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Daily pocketing status, month totals, streaks, and month-to-month observations.
 * Pocketing more is not treated as better — rate and amount are reported apart.
 */
public final class ProfitPocketCalendarMath {

    public static final String FULL = "full";
    public static final String HIGH = "high";
    public static final String MEDIUM = "medium";
    public static final String LOW = "low";
    public static final String MISSED = "missed";
    public static final String UNREVIEWED = "unreviewed";
    public static final String SKIPPED = "skipped";
    public static final String NO_PROFIT = "no_profit";
    public static final String LOSS = "loss";
    public static final String FUTURE = "future";
    public static final String WITHDRAWAL = "withdrawal";

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal P75 = new BigDecimal("75");
    private static final BigDecimal P50 = new BigDecimal("50");
    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private ProfitPocketCalendarMath() {
    }

    public record EntryDraft(
            String id,
            BigDecimal amount,
            BigDecimal attributedAmount,
            LocalDate periodFrom,
            LocalDate periodTo,
            String destinationSummary,
            Instant createdAt,
            String note
    ) {
    }

    public record LogDraft(
            BigDecimal pocketedAmount,
            BigDecimal profitSnapshot,
            String status,
            String note,
            String skipReason,
            String source,
            boolean aboveProfit
    ) {
    }

    public record DayInput(
            LocalDate date,
            BigDecimal liveProfit,
            long saleCount,
            LogDraft log,
            List<EntryDraft> entries
    ) {
    }

    public record DayDraft(
            LocalDate date,
            long saleCount,
            BigDecimal liveProfit,
            BigDecimal profitAmount,
            BigDecimal pocketedAmount,
            BigDecimal remainingProfit,
            BigDecimal pocketingPercentage,
            String status,
            String note,
            String skipReason,
            String source,
            boolean aboveProfit,
            boolean profitMoved,
            List<EntryDraft> entries
    ) {
    }

    public record MonthSummary(
            String month,
            String label,
            BigDecimal totalProfit,
            BigDecimal totalPocketed,
            BigDecimal totalRetained,
            BigDecimal averageDailyPercentage,
            BigDecimal overallPercentage,
            int pocketingDays,
            int partialDays,
            int fullDays,
            int missedDays,
            int unreviewedDays,
            int skippedDays,
            int noProfitDays,
            int longestStreak,
            LocalDate highestPocketDate,
            BigDecimal highestPocketAmount,
            BigDecimal averagePocketedPerDay,
            BigDecimal profitNotPocketed
    ) {
    }

    public record Insight(String code, BigDecimal current, BigDecimal previous, BigDecimal delta) {
    }

    /** Share of a pocket that lands on {@code day}. The last day absorbs rounding. */
    public static BigDecimal allocate(BigDecimal amount, LocalDate from, LocalDate to, LocalDate day) {
        if (amount == null || from == null || to == null || day == null) {
            return ZERO;
        }
        if (day.isBefore(from) || day.isAfter(to)) {
            return ZERO;
        }
        long span = ChronoUnit.DAYS.between(from, to) + 1;
        if (span <= 1) {
            return money(amount);
        }
        BigDecimal each = money(amount).divide(BigDecimal.valueOf(span), 2, RoundingMode.DOWN);
        if (day.equals(to)) {
            BigDecimal prior = each.multiply(BigDecimal.valueOf(span - 1));
            return money(amount).subtract(prior);
        }
        return each;
    }

    public static List<DayDraft> buildDays(List<DayInput> inputs, LocalDate today) {
        List<DayDraft> out = new ArrayList<>();
        for (DayInput input : inputs) {
            out.add(buildDay(input, today));
        }
        return out;
    }

    public static DayDraft buildDay(DayInput input, LocalDate today) {
        BigDecimal live = money(input.liveProfit());
        LogDraft log = input.log();
        boolean skipped = log != null && "skipped".equals(log.status());
        BigDecimal pocketed = log != null
                ? money(log.pocketedAmount())
                : sumAttributed(input.entries());
        BigDecimal profit = log != null && log.profitSnapshot() != null
                ? money(log.profitSnapshot())
                : live;
        boolean profitMoved = log != null
                && log.profitSnapshot() != null
                && live.subtract(money(log.profitSnapshot())).abs().compareTo(new BigDecimal("0.009")) > 0;

        String status = statusOf(input.date(), today, input.saleCount(), profit, pocketed, skipped);
        BigDecimal percentage = percentageOf(profit, pocketed, status);
        BigDecimal remaining = profit.subtract(pocketed).setScale(2, RoundingMode.HALF_UP);
        boolean above = (log != null && log.aboveProfit())
                || (profit.signum() > 0 && pocketed.subtract(profit).compareTo(new BigDecimal("0.009")) > 0)
                || (profit.signum() <= 0 && pocketed.signum() > 0);

        return new DayDraft(
                input.date(),
                input.saleCount(),
                live,
                profit,
                pocketed,
                remaining,
                percentage,
                status,
                log != null ? blankToNull(log.note()) : null,
                log != null ? blankToNull(log.skipReason()) : null,
                log != null ? log.source() : (pocketed.signum() > 0 ? "cash" : null),
                above,
                profitMoved,
                input.entries() == null ? List.of() : List.copyOf(input.entries())
        );
    }

    public static MonthSummary summarize(YearMonth month, List<DayDraft> days) {
        BigDecimal profit = ZERO;
        BigDecimal pocketed = ZERO;
        BigDecimal notPocketed = ZERO;
        BigDecimal pctSum = ZERO;
        int pctDays = 0;
        int pocketingDays = 0;
        int partial = 0;
        int full = 0;
        int missed = 0;
        int unreviewed = 0;
        int skipped = 0;
        int noProfit = 0;
        LocalDate highestDate = null;
        BigDecimal highest = ZERO;

        for (DayDraft day : days) {
            if (day.date() == null || !YearMonth.from(day.date()).equals(month)) {
                continue;
            }
            switch (day.status()) {
                case FUTURE -> {
                    continue;
                }
                case NO_PROFIT -> noProfit++;
                case SKIPPED -> {
                    skipped++;
                    pocketed = pocketed.add(money(day.pocketedAmount()));
                }
                case LOSS -> {
                    noProfit++;
                }
                case MISSED -> {
                    missed++;
                    profit = profit.add(positive(day.profitAmount()));
                    notPocketed = notPocketed.add(positive(day.profitAmount()));
                    pctSum = pctSum.add(ZERO);
                    pctDays++;
                }
                case UNREVIEWED -> {
                    unreviewed++;
                    profit = profit.add(positive(day.profitAmount()));
                    notPocketed = notPocketed.add(positive(day.profitAmount()));
                    pctSum = pctSum.add(ZERO);
                    pctDays++;
                }
                default -> {
                    BigDecimal dayProfit = positive(day.profitAmount());
                    BigDecimal dayPocket = money(day.pocketedAmount());
                    profit = profit.add(dayProfit);
                    pocketed = pocketed.add(dayPocket);
                    BigDecimal gap = dayProfit.subtract(dayPocket);
                    if (gap.signum() > 0) {
                        notPocketed = notPocketed.add(gap);
                    }
                    if (dayPocket.signum() > 0) {
                        pocketingDays++;
                        if (dayPocket.compareTo(highest) > 0) {
                            highest = dayPocket;
                            highestDate = day.date();
                        }
                    }
                    if (FULL.equals(day.status())) {
                        full++;
                    } else if (HIGH.equals(day.status()) || MEDIUM.equals(day.status()) || LOW.equals(day.status())) {
                        partial++;
                    }
                    if (day.pocketingPercentage() != null) {
                        pctSum = pctSum.add(day.pocketingPercentage());
                        pctDays++;
                    }
                }
            }
        }

        BigDecimal retained = profit.subtract(pocketed).setScale(2, RoundingMode.HALF_UP);
        BigDecimal overall = profit.signum() == 0
                ? null
                : pocketed.multiply(HUNDRED).divide(profit, 1, RoundingMode.HALF_UP);
        BigDecimal averageDaily = pctDays == 0
                ? null
                : pctSum.divide(BigDecimal.valueOf(pctDays), 1, RoundingMode.HALF_UP);
        BigDecimal averageAmount = pocketingDays == 0
                ? ZERO
                : pocketed.divide(BigDecimal.valueOf(pocketingDays), 2, RoundingMode.HALF_UP);

        return new MonthSummary(
                month.toString(),
                MONTH_LABEL.format(month),
                money(profit),
                money(pocketed),
                retained,
                averageDaily,
                overall,
                pocketingDays,
                partial,
                full,
                missed,
                unreviewed,
                skipped,
                noProfit,
                longestStreak(days.stream().filter(d -> YearMonth.from(d.date()).equals(month)).toList()),
                highestDate,
                money(highest),
                averageAmount,
                money(notPocketed)
        );
    }

    public static int currentStreak(List<DayDraft> daysNewestLast, LocalDate today) {
        int streak = 0;
        for (int i = daysNewestLast.size() - 1; i >= 0; i--) {
            DayDraft day = daysNewestLast.get(i);
            if (day.date().isAfter(today)) {
                continue;
            }
            if (day.date().equals(today) && day.pocketedAmount().signum() <= 0 && !SKIPPED.equals(day.status())) {
                continue;
            }
            if (day.pocketedAmount().signum() > 0) {
                streak++;
                continue;
            }
            if (NO_PROFIT.equals(day.status()) || LOSS.equals(day.status()) || SKIPPED.equals(day.status())) {
                continue;
            }
            break;
        }
        return streak;
    }

    public static int longestStreak(List<DayDraft> daysNewestLast) {
        int best = 0;
        int run = 0;
        for (DayDraft day : daysNewestLast) {
            if (FUTURE.equals(day.status())) {
                continue;
            }
            if (day.pocketedAmount().signum() > 0) {
                run++;
                best = Math.max(best, run);
                continue;
            }
            if (NO_PROFIT.equals(day.status()) || LOSS.equals(day.status()) || SKIPPED.equals(day.status())) {
                continue;
            }
            run = 0;
        }
        return best;
    }

    public static List<Insight> insights(MonthSummary current, MonthSummary previous) {
        List<Insight> out = new ArrayList<>();
        if (current == null) {
            return out;
        }
        if (current.totalPocketed().compareTo(new BigDecimal("100000")) >= 0) {
            out.add(new Insight("milestone_pocketed", current.totalPocketed(), null, null));
        }
        if (current.pocketingDays() >= 20) {
            out.add(new Insight("milestone_days", BigDecimal.valueOf(current.pocketingDays()), null, null));
        }
        if (previous == null || previous.totalProfit().signum() == 0 && previous.totalPocketed().signum() == 0) {
            return out;
        }
        BigDecimal rateNow = current.overallPercentage();
        BigDecimal rateThen = previous.overallPercentage();
        if (rateNow != null && rateThen != null) {
            BigDecimal rateDelta = rateNow.subtract(rateThen).setScale(1, RoundingMode.HALF_UP);
            if (rateDelta.abs().compareTo(new BigDecimal("0.05")) > 0) {
                out.add(new Insight(
                        rateDelta.signum() > 0 ? "rate_up" : "rate_down",
                        rateNow,
                        rateThen,
                        rateDelta.abs()));
            }
            if (current.totalProfit().compareTo(previous.totalProfit()) > 0 && rateDelta.signum() < 0) {
                out.add(new Insight("profit_up_rate_down", current.totalProfit(), previous.totalProfit(), rateDelta.abs()));
            }
        }
        BigDecimal amountDelta = current.totalPocketed().subtract(previous.totalPocketed());
        if (amountDelta.abs().compareTo(new BigDecimal("0.5")) > 0) {
            out.add(new Insight(
                    amountDelta.signum() > 0 ? "amount_up" : "amount_down",
                    current.totalPocketed(),
                    previous.totalPocketed(),
                    amountDelta.abs()));
        }
        BigDecimal retainedDelta = current.totalRetained().subtract(previous.totalRetained());
        if (retainedDelta.abs().compareTo(new BigDecimal("0.5")) > 0 && current.totalProfit().signum() > 0) {
            out.add(new Insight(
                    retainedDelta.signum() > 0 ? "retained_up" : "retained_down",
                    current.totalRetained(),
                    previous.totalRetained(),
                    retainedDelta.abs()));
        }
        int dayDelta = current.pocketingDays() - previous.pocketingDays();
        if (dayDelta != 0) {
            out.add(new Insight(
                    dayDelta > 0 ? "consistency_up" : "consistency_down",
                    BigDecimal.valueOf(current.pocketingDays()),
                    BigDecimal.valueOf(previous.pocketingDays()),
                    BigDecimal.valueOf(Math.abs(dayDelta))));
        }
        return out;
    }

    private static String statusOf(
            LocalDate date,
            LocalDate today,
            long saleCount,
            BigDecimal profit,
            BigDecimal pocketed,
            boolean skipped
    ) {
        if (date.isAfter(today)) {
            return FUTURE;
        }
        if (skipped && pocketed.signum() <= 0) {
            return SKIPPED;
        }
        if (profit.signum() < 0 && pocketed.signum() <= 0) {
            return LOSS;
        }
        if (profit.signum() <= 0 && pocketed.signum() <= 0 && saleCount == 0) {
            return NO_PROFIT;
        }
        if (profit.signum() <= 0 && pocketed.signum() <= 0) {
            return NO_PROFIT;
        }
        if (profit.signum() <= 0 && pocketed.signum() > 0) {
            return WITHDRAWAL;
        }
        if (pocketed.signum() <= 0) {
            return date.equals(today) ? UNREVIEWED : MISSED;
        }
        BigDecimal pct = pocketed.multiply(HUNDRED).divide(profit, 2, RoundingMode.HALF_UP);
        if (pct.compareTo(HUNDRED) >= 0) {
            return FULL;
        }
        if (pct.compareTo(P75) >= 0) {
            return HIGH;
        }
        if (pct.compareTo(P50) >= 0) {
            return MEDIUM;
        }
        return LOW;
    }

    private static BigDecimal percentageOf(BigDecimal profit, BigDecimal pocketed, String status) {
        if (FUTURE.equals(status) || NO_PROFIT.equals(status) || LOSS.equals(status) || SKIPPED.equals(status)) {
            return null;
        }
        if (profit.signum() <= 0) {
            return null;
        }
        return pocketed.multiply(HUNDRED).divide(profit, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumAttributed(List<EntryDraft> entries) {
        if (entries == null || entries.isEmpty()) {
            return ZERO;
        }
        BigDecimal sum = ZERO;
        for (EntryDraft entry : entries) {
            sum = sum.add(money(entry.attributedAmount()));
        }
        return money(sum);
    }

    private static BigDecimal positive(BigDecimal value) {
        BigDecimal money = money(value);
        return money.signum() > 0 ? money : ZERO;
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null) {
            return ZERO;
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
