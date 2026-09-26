package zelisline.ub.payments.api.dto;

/**
 * Till-safe subset of profit-pocket settings — no destination / rail secrets.
 */
public record MarginGuardSettingsResponse(String marginGuardMode) {
}
