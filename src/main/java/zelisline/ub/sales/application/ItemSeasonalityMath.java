package zelisline.ub.sales.application;

import java.math.BigDecimal;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Calendar-month peaks for a SKU. Season is about the product (all tills),
 * not named customers. Kenyan labels are hints, not a liturgical calendar.
 */
public final class ItemSeasonalityMath {

    private ItemSeasonalityMath() {
    }

    /**
     * Months (1–12) whose qty is at least 80% of the busiest month in the set.
     */
    public static List<Integer> peakMonths(List<BigDecimal> qtyByMonthIndex0) {
        if (qtyByMonthIndex0 == null || qtyByMonthIndex0.isEmpty()) {
            return List.of();
        }
        BigDecimal max = BigDecimal.ZERO;
        for (BigDecimal q : qtyByMonthIndex0) {
            if (q != null && q.compareTo(max) > 0) {
                max = q;
            }
        }
        if (max.compareTo(BigDecimal.ZERO) <= 0) {
            return List.of();
        }
        BigDecimal floor = max.multiply(new BigDecimal("0.80"));
        List<Integer> peaks = new ArrayList<>();
        for (int i = 0; i < qtyByMonthIndex0.size(); i++) {
            BigDecimal q = qtyByMonthIndex0.get(i);
            if (q != null && q.compareTo(floor) >= 0) {
                peaks.add(i + 1);
            }
        }
        return List.copyOf(peaks);
    }

    public static String kenyaSeasonHint(int month) {
        return switch (month) {
            case 12, 1 -> "Festive";
            case 4 -> "Easter";
            case 8 -> "August holiday";
            default -> null;
        };
    }

    public static String monthLabel(int month) {
        if (month < 1 || month > 12) {
            return "";
        }
        String name = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        String hint = kenyaSeasonHint(month);
        return hint == null ? name : name + " · " + hint;
    }
}
