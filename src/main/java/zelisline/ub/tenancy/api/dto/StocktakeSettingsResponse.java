package zelisline.ub.tenancy.api.dto;

public record StocktakeSettingsResponse(
        boolean showSystemStockToStockManager,
        /**
         * How many unique products sold yesterday to include in each day's
         * daily stock audit. Clamped to {@link #MIN_DAILY_AUDIT_SAMPLE_SIZE}–
         * {@link #MAX_DAILY_AUDIT_SAMPLE_SIZE}.
         */
        int dailyAuditSampleSize
) {
    public static final int DEFAULT_DAILY_AUDIT_SAMPLE_SIZE = 25;
    public static final int MIN_DAILY_AUDIT_SAMPLE_SIZE = 1;
    public static final int MAX_DAILY_AUDIT_SAMPLE_SIZE = 200;

    public static StocktakeSettingsResponse defaults() {
        return new StocktakeSettingsResponse(false, DEFAULT_DAILY_AUDIT_SAMPLE_SIZE);
    }

    public static int clampSampleSize(int raw) {
        return Math.max(MIN_DAILY_AUDIT_SAMPLE_SIZE, Math.min(MAX_DAILY_AUDIT_SAMPLE_SIZE, raw));
    }
}
