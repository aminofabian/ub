package zelisline.ub.wholesale.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Kenyan public holidays that stop a supplier's working clock (SUPPLIER_ENGAGEMENT_DESIGN.md D4). Fixed-date
 * holidays and Easter come from the rule below. Dates set by a gazette notice are listed per year; a year
 * with no entry gets only the fixed and Easter holidays, so each new year's gazette must be added here.
 */
final class KenyaPublicHolidays {

    /** Set by gazette notice, per year. 2026: Idd-ul-Fitr (20 March) and Eid al-Adha (27 May). */
    private static final Map<Integer, Set<LocalDate>> GAZETTED = Map.of(
            2026, Set.of(LocalDate.of(2026, 3, 20), LocalDate.of(2026, 5, 27)));

    private KenyaPublicHolidays() {
    }

    /** Holidays between two dates, inclusive. */
    static Set<LocalDate> between(LocalDate from, LocalDate to) {
        Set<LocalDate> out = new HashSet<>();
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            out.add(LocalDate.of(year, 1, 1));
            out.add(LocalDate.of(year, 5, 1));
            out.add(LocalDate.of(year, 6, 1));
            out.add(LocalDate.of(year, 10, 20));
            out.add(LocalDate.of(year, 12, 12));
            out.add(LocalDate.of(year, 12, 25));
            out.add(LocalDate.of(year, 12, 26));
            LocalDate easter = easterSunday(year);
            out.add(easter.minusDays(2));
            out.add(easter.plusDays(1));
            out.addAll(GAZETTED.getOrDefault(year, Set.of()));
        }
        out.removeIf(d -> d.isBefore(from) || d.isAfter(to));
        // Only the days a working clock could fall on matter; keep the set small.
        out.removeIf(d -> d.getDayOfWeek() == DayOfWeek.SUNDAY);
        return out;
    }

    /** Easter Sunday, Anonymous Gregorian algorithm (Meeus/Jones/Butcher). */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
