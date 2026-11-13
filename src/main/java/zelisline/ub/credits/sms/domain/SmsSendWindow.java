package zelisline.ub.credits.sms.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Hours in which promotional SMS may be sent: 08:00 to 18:00, Africa/Nairobi. Published
 * guidance on the Kenyan limit differs (some sources allow 07:00 to 19:00); this is the
 * narrowest window found, to be widened once the SMS provider confirms (ADR 0013).
 * Transactional texts are not restricted.
 */
public final class SmsSendWindow {

    public static final ZoneId ZONE = ZoneId.of("Africa/Nairobi");
    public static final LocalTime OPENS = LocalTime.of(8, 0);
    public static final LocalTime CLOSES = LocalTime.of(18, 0);

    private SmsSendWindow() {
    }

    public static boolean isOpen(Instant at) {
        LocalTime time = at.atZone(ZONE).toLocalTime();
        return !time.isBefore(OPENS) && time.isBefore(CLOSES);
    }

    /** {@code at} itself when the window is open, otherwise the next opening time. */
    public static Instant nextOpen(Instant at) {
        if (isOpen(at)) {
            return at;
        }
        ZonedDateTime local = at.atZone(ZONE);
        ZonedDateTime opening = local.toLocalDate().atTime(OPENS).atZone(ZONE);
        if (!local.toLocalTime().isBefore(CLOSES)) {
            opening = opening.plusDays(1);
        }
        return opening.toInstant();
    }

    public static String describe() {
        return "8:00 am to 6:00 pm";
    }
}
