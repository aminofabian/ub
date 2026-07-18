package zelisline.ub.tenancy.api.dto;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public record StocktakeSettingsResponse(
        boolean showSystemStockToStockManager,
        /**
         * How many unique products sold yesterday to include in each day's
         * daily stock audit. Clamped to {@link #MIN_DAILY_AUDIT_SAMPLE_SIZE}–
         * {@link #MAX_DAILY_AUDIT_SAMPLE_SIZE}.
         */
        int dailyAuditSampleSize,
        /** Local wall-clock time when morning counting opens ({@code HH:mm}). */
        String morningStartsAt,
        /** Local wall-clock time when morning counting closes ({@code HH:mm}). */
        String morningEndsAt,
        /** Local wall-clock time when evening counting opens ({@code HH:mm}). */
        String eveningStartsAt,
        /** Local wall-clock time when evening counting closes ({@code HH:mm}). */
        String eveningEndsAt
) {
    public static final int DEFAULT_DAILY_AUDIT_SAMPLE_SIZE = 25;
    public static final int MIN_DAILY_AUDIT_SAMPLE_SIZE = 1;
    public static final int MAX_DAILY_AUDIT_SAMPLE_SIZE = 200;

    public static final String DEFAULT_MORNING_STARTS_AT = "08:00";
    public static final String DEFAULT_MORNING_ENDS_AT = "09:00";
    public static final String DEFAULT_EVENING_STARTS_AT = "20:00";
    public static final String DEFAULT_EVENING_ENDS_AT = "21:00";

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public static StocktakeSettingsResponse defaults() {
        return new StocktakeSettingsResponse(
                false,
                DEFAULT_DAILY_AUDIT_SAMPLE_SIZE,
                DEFAULT_MORNING_STARTS_AT,
                DEFAULT_MORNING_ENDS_AT,
                DEFAULT_EVENING_STARTS_AT,
                DEFAULT_EVENING_ENDS_AT
        );
    }

    public static int clampSampleSize(int raw) {
        return Math.max(MIN_DAILY_AUDIT_SAMPLE_SIZE, Math.min(MAX_DAILY_AUDIT_SAMPLE_SIZE, raw));
    }

    /**
     * Parses and normalizes an {@code HH:mm} string. Blank/null falls back to {@code fallback}.
     * Invalid values throw {@link IllegalArgumentException}.
     */
    public static String normalizeTimeOrDefault(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return normalizeTime(fallback);
        }
        return normalizeTime(raw.trim());
    }

    public static String normalizeTime(String raw) {
        LocalTime time = parseTime(raw);
        return time.format(HH_MM);
    }

    public static LocalTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Time is required (HH:mm)");
        }
        try {
            return LocalTime.parse(raw.trim(), HH_MM);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("Invalid time '" + raw + "' (expected HH:mm)");
        }
    }

    /**
     * Ensures morning and evening windows are valid and do not overlap:
     * {@code morningStart < morningEnd <= eveningStart < eveningEnd}.
     */
    public static void requireOrderedSchedule(
            String morningStartsAt,
            String morningEndsAt,
            String eveningStartsAt,
            String eveningEndsAt
    ) {
        LocalTime morningStart = parseTime(morningStartsAt);
        LocalTime morningEnd = parseTime(morningEndsAt);
        LocalTime eveningStart = parseTime(eveningStartsAt);
        LocalTime eveningEnd = parseTime(eveningEndsAt);
        if (!morningStart.isBefore(morningEnd)) {
            throw new IllegalArgumentException(
                    "Morning window must satisfy morningStartsAt < morningEndsAt"
            );
        }
        if (!eveningStart.isBefore(eveningEnd)) {
            throw new IllegalArgumentException(
                    "Evening window must satisfy eveningStartsAt < eveningEndsAt"
            );
        }
        if (morningEnd.isAfter(eveningStart)) {
            throw new IllegalArgumentException(
                    "Morning must end at or before evening starts (morningEndsAt <= eveningStartsAt)"
            );
        }
    }
}
