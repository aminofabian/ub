package zelisline.ub.payments.application;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parses and normalizes tenant auto-pay clock times ({@code HH:mm}).
 */
public final class SupplierAutoPayTimes {

    public static final List<String> DEFAULT_TIMES = List.of("00:00", "18:00");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final int MAX_TIMES = 8;

    private SupplierAutoPayTimes() {
    }

    public static List<String> defaults() {
        return List.copyOf(DEFAULT_TIMES);
    }

    public static List<String> parseOrDefault(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return defaults();
        }
        try {
            List<String> raw = mapper.readValue(json, new TypeReference<List<String>>() {
            });
            List<String> normalized = normalize(raw);
            return normalized.isEmpty() ? defaults() : normalized;
        } catch (Exception e) {
            return defaults();
        }
    }

    public static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            unique.add(normalizeOne(item.trim()));
            if (unique.size() >= MAX_TIMES) {
                break;
            }
        }
        List<String> sorted = new ArrayList<>(unique);
        sorted.sort((a, b) -> LocalTime.parse(a, HM).compareTo(LocalTime.parse(b, HM)));
        return sorted;
    }

    public static List<String> requireValid(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Add at least one auto-pay time (HH:mm)");
        }
        try {
            List<String> normalized = normalize(raw);
            if (normalized.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Add at least one valid auto-pay time (HH:mm)");
            }
            return normalized;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Auto-pay times must be HH:mm (e.g. 00:00, 18:00)");
        }
    }

    public static String toJson(List<String> times, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(times);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize auto-pay times", e);
        }
    }

    public static boolean matchesMinute(List<String> timesHm, LocalTime now) {
        if (timesHm == null || timesHm.isEmpty()) {
            return false;
        }
        String current = now.withSecond(0).withNano(0).format(HM);
        return timesHm.contains(current);
    }

    private static String normalizeOne(String raw) {
        String s = raw.trim();
        // Accept H:mm → HH:mm
        if (s.matches("^\\d:\\d{2}$")) {
            s = "0" + s;
        }
        try {
            LocalTime t = LocalTime.parse(s, HM);
            return t.format(HM);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid time: " + raw);
        }
    }
}
