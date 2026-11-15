package zelisline.ub.serviceops.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What one person earns on particular services when it differs from their plan and from the
 * service's own rate (ADR 0021). Stored on the staff profile as
 * {@code serviceId:PERCENT:60.00;serviceId:FIXED:150.00}.
 */
public final class StaffServiceRates {

    public static final int MAX_RATES = 80;

    private StaffServiceRates() {
    }

    public record Rate(String serviceId, String type, BigDecimal value) {
    }

    public static List<Rate> decode(String stored) {
        List<Rate> out = new ArrayList<>();
        if (stored == null || stored.isBlank()) {
            return out;
        }
        for (String part : stored.split(";")) {
            String[] bits = part.split(":", 3);
            if (bits.length != 3 || !ServiceCommissionTypes.isKnown(bits[1])) {
                continue;
            }
            try {
                out.add(new Rate(bits[0], bits[1], new BigDecimal(bits[2]).setScale(2, RoundingMode.HALF_UP)));
            } catch (NumberFormatException ex) {
                // Skip a damaged entry.
            }
        }
        return out;
    }

    public static String encode(List<Rate> rates) {
        if (rates == null || rates.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        for (Rate rate : rates) {
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(rate.serviceId()).append(':').append(rate.type()).append(':')
                    .append(rate.value().setScale(2, RoundingMode.HALF_UP).toPlainString());
        }
        return out.toString();
    }

    public static Optional<Rate> find(String stored, String serviceId) {
        if (serviceId == null || stored == null || !stored.contains(serviceId)) {
            return Optional.empty();
        }
        return decode(stored).stream().filter(r -> serviceId.equals(r.serviceId())).findFirst();
    }
}
