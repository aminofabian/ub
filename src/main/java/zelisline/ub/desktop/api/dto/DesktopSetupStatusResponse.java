package zelisline.ub.desktop.api.dto;

/**
 * Shape consumed by the frontend's root router to decide between {@code /setup}
 * (first-run wizard) and {@code /login}.
 *
 * @param setupRequired {@code true} when no {@code Business} row exists for the
 *     configured {@code app.desktop.business-id} — the UI must route to the
 *     wizard.
 * @param businessId the configured desktop business ID. Surfaced so support
 *     can sanity-check the wiring without needing DB access.
 */
public record DesktopSetupStatusResponse(
        boolean setupRequired,
        String businessId
) {
}
