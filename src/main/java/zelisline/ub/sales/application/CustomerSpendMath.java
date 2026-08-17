package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Shopping rhythm for a Kenyan dukas: weekly visits matter more than daily ones.
 */
public final class CustomerSpendMath {

    private static final LocalDate EPOCH_MONDAY = LocalDate.of(1970, 1, 5);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private CustomerSpendMath() {
    }

    public record Rhythm(
            int visitDays,
            int weekStreak,
            int longestWeekStreak,
            Integer avgGapDays,
            String cadence,
            String favoriteWeekday
    ) {
    }

    public static String displayName(String firstName, String lastName, String fallback) {
        String built = joinName(firstName, lastName);
        String raw = !built.isBlank() ? built : (fallback == null ? "" : fallback.trim());
        if (raw.isBlank()) {
            return "Customer";
        }
        if (isShouting(raw)) {
            return titleCase(raw);
        }
        return raw;
    }

    public static Rhythm rhythm(List<LocalDate> visitDates, LocalDate asOf) {
        if (visitDates == null || visitDates.isEmpty() || asOf == null) {
            return new Rhythm(0, 0, 0, null, "Once", null);
        }
        TreeSet<LocalDate> days = new TreeSet<>();
        for (LocalDate d : visitDates) {
            if (d != null) {
                days.add(d);
            }
        }
        if (days.isEmpty()) {
            return new Rhythm(0, 0, 0, null, "Once", null);
        }

        TreeSet<Long> weeks = new TreeSet<>();
        EnumMap<DayOfWeek, Integer> weekdayHits = new EnumMap<>(DayOfWeek.class);
        for (LocalDate d : days) {
            weeks.add(weekIndex(d));
            weekdayHits.merge(d.getDayOfWeek(), 1, Integer::sum);
        }

        int longest = longestConsecutive(weeks);
        int current = currentWeekStreak(weeks, weekIndex(days.last()), weekIndex(asOf));
        Integer avgGap = averageGapDays(days);
        return new Rhythm(
                days.size(),
                current,
                longest,
                avgGap,
                cadence(days.size(), avgGap),
                favoriteWeekday(weekdayHits));
    }

    public static String cohort(
            long saleCount,
            LocalDate firstVisit,
            LocalDate lastVisit,
            LocalDate asOf
    ) {
        if (lastVisit == null || asOf == null || saleCount <= 0) {
            return "one_off";
        }
        long daysSince = ChronoUnit.DAYS.between(lastVisit, asOf);
        long tenure = firstVisit == null ? daysSince : ChronoUnit.DAYS.between(firstVisit, asOf);
        if (tenure <= 14 && saleCount <= 2) {
            return "new_face";
        }
        if (daysSince > 45) {
            return "dormant";
        }
        if (daysSince > 14) {
            return "at_risk";
        }
        if (saleCount == 1) {
            return "one_off";
        }
        return "regular";
    }

    /**
     * Top fifth of identified spend, still shopping in the last two weeks, with at least
     * three tills in the window — the names the shop cannot afford to lose.
     */
    public static Set<String> championIds(
            List<SpendRank> ranks,
            LocalDate asOf
    ) {
        if (ranks == null || ranks.isEmpty() || asOf == null) {
            return Set.of();
        }
        List<SpendRank> bySpend = new ArrayList<>(ranks);
        bySpend.sort((a, b) -> b.spend().compareTo(a.spend()));
        int cutoff = Math.max(1, (int) Math.ceil(bySpend.size() * 0.2d));
        Set<String> top = new HashSet<>();
        for (int i = 0; i < cutoff && i < bySpend.size(); i++) {
            top.add(bySpend.get(i).customerId());
        }
        Set<String> champions = new HashSet<>();
        for (SpendRank row : ranks) {
            if (!top.contains(row.customerId())) {
                continue;
            }
            if (row.saleCount() < 3 || row.lastVisit() == null) {
                continue;
            }
            if (ChronoUnit.DAYS.between(row.lastVisit(), asOf) > 14) {
                continue;
            }
            champions.add(row.customerId());
        }
        return champions;
    }

    public static BigDecimal sharePct(BigDecimal spend, BigDecimal identifiedSpend) {
        if (spend == null || identifiedSpend == null || identifiedSpend.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return spend.multiply(HUNDRED).divide(identifiedSpend, 1, RoundingMode.HALF_UP);
    }

    public record SpendRank(
            String customerId,
            BigDecimal spend,
            long saleCount,
            LocalDate lastVisit
    ) {
    }

    static long weekIndex(LocalDate date) {
        LocalDate monday = date.with(DayOfWeek.MONDAY);
        return ChronoUnit.WEEKS.between(EPOCH_MONDAY, monday);
    }

    static int currentWeekStreak(Set<Long> weeks, long lastVisitWeek, long asOfWeek) {
        if (weeks == null || weeks.isEmpty()) {
            return 0;
        }
        if (asOfWeek - lastVisitWeek > 1) {
            return 0;
        }
        int streak = 0;
        long cursor = lastVisitWeek;
        while (weeks.contains(cursor)) {
            streak++;
            cursor--;
        }
        return streak;
    }

    static int longestConsecutive(TreeSet<Long> weeks) {
        if (weeks == null || weeks.isEmpty()) {
            return 0;
        }
        int best = 1;
        int run = 1;
        Long prev = null;
        for (Long week : weeks) {
            if (prev != null && week == prev + 1) {
                run++;
                if (run > best) {
                    best = run;
                }
            } else {
                run = 1;
            }
            prev = week;
        }
        return best;
    }

    private static Integer averageGapDays(TreeSet<LocalDate> days) {
        if (days.size() < 2) {
            return null;
        }
        long span = ChronoUnit.DAYS.between(days.first(), days.last());
        return (int) Math.round((double) span / (days.size() - 1));
    }

    private static String cadence(int visitDays, Integer avgGapDays) {
        if (visitDays <= 1 || avgGapDays == null) {
            return "Once";
        }
        if (avgGapDays <= 3) {
            return "Almost daily";
        }
        if (avgGapDays <= 9) {
            return "Weekly";
        }
        if (avgGapDays <= 18) {
            return "Fortnightly";
        }
        return "Now and then";
    }

    private static String favoriteWeekday(Map<DayOfWeek, Integer> hits) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        DayOfWeek best = null;
        int bestCount = 0;
        for (DayOfWeek day : DayOfWeek.values()) {
            int count = hits.getOrDefault(day, 0);
            if (count > bestCount) {
                best = day;
                bestCount = count;
            }
        }
        if (best == null || bestCount <= 0) {
            return null;
        }
        return best.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    private static String joinName(String firstName, String lastName) {
        String first = firstName == null ? "" : firstName.trim();
        String last = lastName == null ? "" : lastName.trim();
        if (first.isEmpty()) {
            return last;
        }
        if (last.isEmpty()) {
            return first;
        }
        return first + " " + last;
    }

    private static boolean isShouting(String raw) {
        boolean letter = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetter(c)) {
                letter = true;
                if (Character.isLowerCase(c)) {
                    return false;
                }
            }
        }
        return letter;
    }

    private static String titleCase(String raw) {
        String[] parts = raw.toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.toString();
    }

    static Set<Long> weeksOf(List<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) {
            return Collections.emptySet();
        }
        TreeSet<Long> weeks = new TreeSet<>();
        for (LocalDate d : dates) {
            weeks.add(weekIndex(d));
        }
        return weeks;
    }
}
