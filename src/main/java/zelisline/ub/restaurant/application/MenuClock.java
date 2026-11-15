package zelisline.ub.restaurant.application;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import zelisline.ub.restaurant.domain.MenuItem;

/**
 * Menus by time of day and happy hour (ADR 0020, amendment 3). Times are minutes after midnight
 * in Nairobi. A window whose end is before its start runs past midnight (22:00 to 02:00); after
 * midnight it counts as the day it began on.
 */
public final class MenuClock {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private MenuClock() {
    }

    /** The moment a menu rule is judged at. */
    public record At(int minute, int dayOfWeek) {

        public static At now() {
            ZonedDateTime now = ZonedDateTime.now(NAIROBI);
            return new At(now.getHour() * 60 + now.getMinute(), now.getDayOfWeek().getValue());
        }
    }

    /** True when the item is served at that moment: it has no serving hours, or the moment is inside them. */
    public static boolean served(MenuItem item, At at) {
        return item.getServeFromMinute() == null || item.getServeToMinute() == null
                || within(item.getServeFromMinute(), item.getServeToMinute(), at.minute());
    }

    /** The dish's price at that moment: its happy-hour price inside happy hour, else its ordinary price. */
    public static BigDecimal price(MenuItem item, At at) {
        return happy(item, at) ? item.getHappyPrice() : item.getPrice();
    }

    public static boolean happy(MenuItem item, At at) {
        if (item.getHappyPrice() == null || item.getHappyPrice().signum() <= 0
                || item.getHappyFromMinute() == null || item.getHappyToMinute() == null) {
            return false;
        }
        int from = item.getHappyFromMinute();
        int to = item.getHappyToMinute();
        if (!within(from, to, at.minute())) {
            return false;
        }
        String days = item.getHappyDays();
        if (days == null || days.isBlank()) {
            return true;
        }
        // Past midnight in a window that began the evening before belongs to that evening.
        int day = from > to && at.minute() < to ? (at.dayOfWeek() == 1 ? 7 : at.dayOfWeek() - 1) : at.dayOfWeek();
        return days.indexOf((char) ('0' + day)) >= 0;
    }

    /** From inclusive, to exclusive; equal means the whole day. */
    static boolean within(int from, int to, int minute) {
        if (from == to) {
            return true;
        }
        return from < to ? minute >= from && minute < to : minute >= from || minute < to;
    }
}
