package zelisline.ub.finance;

import java.time.ZoneId;
import java.time.ZoneOffset;

import zelisline.ub.tenancy.domain.Business;

/**
 * Canonical business calendar timezone for finance day windows.
 *
 * <p>Same reference as {@code RecurringExpenseService} nightly posting: when a request
 * omits {@code date}, "today" is {@code LocalDate.now(zone)}. Instant windows for pulse
 * sales span {@code [day 00:00, nextDay 00:00)} in this zone — never UTC midnight of the
 * calendar date alone.
 */
public final class BusinessTimeZones {

    public static final String DEFAULT_ZONE_ID = "Africa/Nairobi";

    private BusinessTimeZones() {
    }

    public static ZoneId of(Business business) {
        if (business == null) {
            return ZoneId.of(DEFAULT_ZONE_ID);
        }
        return of(business.getTimezone());
    }

    public static ZoneId of(String raw) {
        if (raw == null || raw.isBlank()) {
            return ZoneId.of(DEFAULT_ZONE_ID);
        }
        try {
            return ZoneId.of(raw.trim());
        } catch (Exception e) {
            return ZoneOffset.UTC;
        }
    }
}
