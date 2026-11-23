package zelisline.ub.wholesale.application;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * Public holidays set by gazette notice (Idd-ul-Fitr, Eid al-Adha, a one-off day) are not known in advance. They
 * are listed in {@code supplier-engagement.extra-holidays} as ISO dates separated by commas, for any year, so a
 * new year needs a line of configuration and no code change. A date that cannot be read is skipped and logged.
 */
@Component
class SupplierHolidayConfig {

    private static final Logger log = LoggerFactory.getLogger(SupplierHolidayConfig.class);

    @Value("${supplier-engagement.extra-holidays:}")
    private String extraHolidays;

    @PostConstruct
    void load() {
        Set<LocalDate> dates = new HashSet<>();
        if (extraHolidays != null) {
            for (String raw : extraHolidays.split(",")) {
                String value = raw.trim();
                if (value.isEmpty()) {
                    continue;
                }
                try {
                    dates.add(LocalDate.parse(value));
                } catch (DateTimeParseException e) {
                    log.warn("Skipping an extra holiday that is not a date: {}", value);
                }
            }
        }
        KenyaPublicHolidays.setExtra(dates);
    }
}
