package zelisline.ub.tenancy.api.dto;

/** Live alert prefs for the business hub (/business). */
public record HubAlertsSettingsResponse(
        /** Chime loudness 1–100. Default 45 (~current product gain). */
        int volume
) {
    public static final int DEFAULT_VOLUME = 45;

    public static HubAlertsSettingsResponse defaults() {
        return new HubAlertsSettingsResponse(DEFAULT_VOLUME);
    }
}
