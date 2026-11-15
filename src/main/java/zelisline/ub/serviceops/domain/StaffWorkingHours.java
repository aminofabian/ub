package zelisline.ub.serviceops.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The days and hours one person works (ADR 0021). Stored on the staff profile as
 * {@code mon=08:00-17:00;tue=08:00-17:00}. A day that is not listed is a day they do not work;
 * nothing stored at all means they work whenever the shop is open.
 */
public final class StaffWorkingHours {

    public static final List<String> DAY_KEYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

    /** Shops here keep Nairobi time; a booking is checked against the hours on that clock. */
    public static final ZoneId ZONE = ZoneId.of("Africa/Nairobi");

    private StaffWorkingHours() {
    }

    public record Day(String open, String close) {
    }

    public static Map<String, Day> decode(String stored) {
        Map<String, Day> out = new LinkedHashMap<>();
        if (stored == null || stored.isBlank()) {
            return out;
        }
        for (String part : stored.split(";")) {
            String[] bits = part.split("=", 2);
            String[] times = bits.length == 2 ? bits[1].split("-", 2) : new String[0];
            if (times.length == 2 && DAY_KEYS.contains(bits[0]) && isTime(times[0]) && isTime(times[1])) {
                out.put(bits[0], new Day(times[0], times[1]));
            }
        }
        return out;
    }

    /** Days are written in week order. No days gives {@code null}: the person follows the shop. */
    public static String encode(Map<String, Day> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (String key : DAY_KEYS) {
            Day day = days.get(key);
            if (day == null) {
                continue;
            }
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(key).append('=').append(day.open()).append('-').append(day.close());
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** Whether the whole of a window falls inside this person's hours. True when they have none set. */
    public static boolean works(String stored, Instant start, Instant end) {
        Map<String, Day> days = decode(stored);
        if (days.isEmpty()) {
            return true;
        }
        ZonedDateTime from = start.atZone(ZONE);
        ZonedDateTime to = end.atZone(ZONE);
        if (!from.toLocalDate().equals(to.toLocalDate())) {
            return false;
        }
        Day day = days.get(DAY_KEYS.get(from.getDayOfWeek().getValue() - 1));
        return day != null
                && !from.toLocalTime().isBefore(LocalTime.parse(day.open()))
                && !to.toLocalTime().isAfter(LocalTime.parse(day.close()));
    }

    public static boolean isTime(String value) {
        return value != null && value.matches("^([01]\\d|2[0-3]):[0-5]\\d$");
    }
}
