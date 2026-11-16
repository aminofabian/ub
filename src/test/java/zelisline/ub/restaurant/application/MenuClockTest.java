package zelisline.ub.restaurant.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import zelisline.ub.restaurant.application.MenuClock.At;
import zelisline.ub.restaurant.domain.MenuCategory;
import zelisline.ub.restaurant.domain.MenuItem;

/** Serving windows, including named menus (a window on the heading). Minutes after midnight. */
class MenuClockTest {

    private static final int H6 = 6 * 60, H11 = 11 * 60, H16 = 16 * 60, H22 = 22 * 60, H2 = 2 * 60;

    private static At at(int minute) {
        return new At(minute, 3); // a Wednesday; the day only matters for happy hour
    }

    private static MenuItem item(Integer from, Integer to) {
        MenuItem i = new MenuItem();
        i.setServeFromMinute(from);
        i.setServeToMinute(to);
        return i;
    }

    private static MenuCategory heading(Integer from, Integer to) {
        MenuCategory c = new MenuCategory();
        c.setServeFromMinute(from);
        c.setServeToMinute(to);
        return c;
    }

    @Test
    void anItemWithNoHoursIsServedAllDay() {
        assertThat(MenuClock.served(item(null, null), at(H2))).isTrue();
        assertThat(MenuClock.served(item(null, null), at(H16))).isTrue();
    }

    @Test
    void anItemIsServedInsideItsOwnWindowOnly() {
        MenuItem breakfast = item(H6, H11);
        assertThat(MenuClock.served(breakfast, at(H6))).isTrue();       // from is inclusive
        assertThat(MenuClock.served(breakfast, at(H6 + 90))).isTrue();
        assertThat(MenuClock.served(breakfast, at(H11))).isFalse();     // to is exclusive
        assertThat(MenuClock.served(breakfast, at(H2))).isFalse();
    }

    @Test
    void aWindowThatEndsBeforeItStartsRunsPastMidnight() {
        MenuItem lateBar = item(H22, H2); // 22:00 to 02:00
        assertThat(MenuClock.served(lateBar, at(H22 + 30))).isTrue();
        assertThat(MenuClock.served(lateBar, at(30))).isTrue();   // 00:30
        assertThat(MenuClock.served(lateBar, at(H16))).isFalse();
    }

    @Test
    void aNamedMenuIsOpenInsideItsWindow() {
        assertThat(MenuClock.served(heading(null, null), at(H16))).isTrue();   // all day
        assertThat(MenuClock.served((MenuCategory) null, at(H16))).isTrue();   // no heading
        MenuCategory lunch = heading(H11, H16);
        assertThat(MenuClock.served(lunch, at(H11 + 60))).isTrue();
        assertThat(MenuClock.served(lunch, at(H6))).isFalse();
    }

    @Test
    void aDishIsServedOnlyWhenBothItsNamedMenuAndItsOwnHoursAllow() {
        MenuCategory breakfast = heading(H6, H11);

        // A plain dish under a breakfast heading follows the heading.
        MenuItem plain = item(null, null);
        assertThat(MenuClock.served(plain, breakfast, at(H6 + 30))).isTrue();
        assertThat(MenuClock.served(plain, breakfast, at(H16))).isFalse();

        // A dish with tighter hours than its heading is off outside its own window.
        MenuItem earlyOnly = item(H6, H6 + 60); // only the first breakfast hour
        assertThat(MenuClock.served(earlyOnly, breakfast, at(H6 + 30))).isTrue();
        assertThat(MenuClock.served(earlyOnly, breakfast, at(H6 + 90))).isFalse(); // heading open, dish closed

        // A dish whose own hours fall outside its heading is never served (both must agree).
        MenuItem afternoonDish = item(H16, H22);
        assertThat(MenuClock.served(afternoonDish, breakfast, at(H16 + 30))).isFalse(); // dish open, heading closed
    }
}
