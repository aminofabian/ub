package zelisline.ub.tenancy.api.dto;

/**
 * Business hub live alerts on {@code /business}.
 * Absent flags keep documented defaults (both beeps on).
 */
public record HubAlertsFeatureFlagsPatch(
        /** Beep when a POS sale completes. Default true. */
        Boolean beepOnSale,
        /** Beep when a supply bill is posted. Default true. */
        Boolean beepOnSupply
) {
}
