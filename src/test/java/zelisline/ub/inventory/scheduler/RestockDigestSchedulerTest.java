package zelisline.ub.inventory.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/** Due-window arithmetic for the nightly slot claim. */
class RestockDigestSchedulerTest {

    private static final LocalTime EIGHT_PM = LocalTime.of(20, 0);

    @Test
    void firesOnTheExactMinute() {
        assertThat(RestockDigestScheduler.isDue(EIGHT_PM, EIGHT_PM)).isTrue();
    }

    @Test
    void notDueBeforeRunTime() {
        assertThat(RestockDigestScheduler.isDue(LocalTime.of(19, 59), EIGHT_PM)).isFalse();
        assertThat(RestockDigestScheduler.isDue(LocalTime.of(6, 0), EIGHT_PM)).isFalse();
    }

    @Test
    void catchesUpAfterAMissedMinute() {
        // A deploy straddling 20:00 must not lose the night's digest.
        assertThat(RestockDigestScheduler.isDue(LocalTime.of(20, 7), EIGHT_PM)).isTrue();
        assertThat(RestockDigestScheduler.isDue(LocalTime.of(21, 30), EIGHT_PM)).isTrue();
    }

    @Test
    void stopsCatchingUpAfterTheWindow() {
        assertThat(RestockDigestScheduler.isDue(LocalTime.of(21, 31), EIGHT_PM)).isFalse();
        assertThat(RestockDigestScheduler.isDue(LocalTime.of(23, 59), EIGHT_PM)).isFalse();
    }

    @Test
    void runTimeWithSecondsStillMatches() {
        // restock_run_time is a MySQL TIME; seconds must not stop the claim.
        assertThat(RestockDigestScheduler.isDue(EIGHT_PM, LocalTime.of(20, 0, 45))).isTrue();
    }

    @Test
    void lateRunTimeDoesNotWrapPastMidnight() {
        LocalTime elevenThirty = LocalTime.of(23, 30);
        assertThat(RestockDigestScheduler.isDue(LocalTime.of(23, 45), elevenThirty)).isTrue();
        // 00:15 the next day belongs to the next run date, not this one.
        assertThat(RestockDigestScheduler.isDue(LocalTime.of(0, 15), elevenThirty)).isFalse();
    }
}
