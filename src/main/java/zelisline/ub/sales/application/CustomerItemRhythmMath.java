package zelisline.ub.sales.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * Person × SKU reorder gap. Median of gaps between distinct purchase days;
 * due-ish when days since last &gt; 1.3 × that median.
 */
public final class CustomerItemRhythmMath {

    public static final int MIN_PURCHASES = 3;
    public static final double DUE_FACTOR = 1.3d;

    private CustomerItemRhythmMath() {
    }

    public static Integer medianGapDays(List<LocalDate> purchaseDays) {
        if (purchaseDays == null || purchaseDays.isEmpty()) {
            return null;
        }
        TreeSet<LocalDate> days = new TreeSet<>();
        for (LocalDate d : purchaseDays) {
            if (d != null) {
                days.add(d);
            }
        }
        if (days.size() < 2) {
            return null;
        }
        List<Integer> gaps = new ArrayList<>();
        LocalDate prev = null;
        for (LocalDate d : days) {
            if (prev != null) {
                gaps.add((int) ChronoUnit.DAYS.between(prev, d));
            }
            prev = d;
        }
        Collections.sort(gaps);
        int mid = gaps.size() / 2;
        if (gaps.size() % 2 == 1) {
            return gaps.get(mid);
        }
        return (int) Math.round((gaps.get(mid - 1) + gaps.get(mid)) / 2.0d);
    }

    public static boolean dueish(int daysSinceLast, Integer medianGapDays) {
        if (medianGapDays == null || medianGapDays <= 0 || daysSinceLast < 0) {
            return false;
        }
        return daysSinceLast > medianGapDays * DUE_FACTOR;
    }

    /** Shop copy: "Usually every 3 weeks — last bought 5 weeks ago". */
    public static String describe(Integer medianGapDays, int daysSinceLast, boolean due) {
        if (medianGapDays == null || medianGapDays <= 0) {
            return daysSinceLast <= 0
                    ? "Last bought today"
                    : "Last bought " + formatSpan(daysSinceLast) + " ago";
        }
        String usual = "Usually every " + formatSpan(medianGapDays);
        String last = daysSinceLast <= 0
                ? "last bought today"
                : "last bought " + formatSpan(daysSinceLast) + " ago";
        return due ? usual + " — " + last : usual + " · " + last;
    }

    static String formatSpan(int days) {
        int n = Math.max(0, days);
        if (n <= 1) {
            return n == 1 ? "1 day" : "today";
        }
        if (n < 7) {
            return n + " days";
        }
        int weeks = (int) Math.round(n / 7.0d);
        if (weeks < 9) {
            return weeks == 1 ? "1 week" : weeks + " weeks";
        }
        int months = Math.max(1, (int) Math.round(n / 30.0d));
        return months == 1 ? "1 month" : months + " months";
    }
}
