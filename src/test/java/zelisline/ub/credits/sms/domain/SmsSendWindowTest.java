package zelisline.ub.credits.sms.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class SmsSendWindowTest {

    private static Instant nairobi(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 11, day, hour, minute).atZone(SmsSendWindow.ZONE).toInstant();
    }

    @Test
    void openFromEightUntilSixNairobiTime() {
        assertThat(SmsSendWindow.isOpen(nairobi(13, 7, 59))).isFalse();
        assertThat(SmsSendWindow.isOpen(nairobi(13, 8, 0))).isTrue();
        assertThat(SmsSendWindow.isOpen(nairobi(13, 17, 59))).isTrue();
        assertThat(SmsSendWindow.isOpen(nairobi(13, 18, 0))).isFalse();
    }

    @Test
    void nextOpenIsUnchangedInsideTheWindow() {
        Instant noon = nairobi(13, 12, 0);
        assertThat(SmsSendWindow.nextOpen(noon)).isEqualTo(noon);
    }

    @Test
    void earlyMorningMovesToEightTheSameDay() {
        assertThat(SmsSendWindow.nextOpen(nairobi(13, 6, 30))).isEqualTo(nairobi(13, 8, 0));
    }

    @Test
    void eveningMovesToEightTheNextDay() {
        assertThat(SmsSendWindow.nextOpen(nairobi(13, 21, 15))).isEqualTo(nairobi(14, 8, 0));
        assertThat(SmsSendWindow.nextOpen(nairobi(13, 18, 0))).isEqualTo(nairobi(14, 8, 0));
    }
}
