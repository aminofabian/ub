package zelisline.ub.desktop.api.dto;

/**
 * Shape consumed by the frontend's root router to decide between {@code /setup}
 * (first-run wizard) and {@code /login}.
 *
 * @param setupRequired {@code true} when no {@code Business} row exists for the
 *     configured {@code app.desktop.business-id} — the UI must route to the
 *     wizard.
 * @param businessId the configured desktop business ID.
 * @param shopName local business display name when setup is complete.
 * @param shopHost storefront host when known (e.g. {@code palmart.kiosk.ke}).
 * @param cloudOrigin online API origin from {@code cloud-sync.json} when connected.
 */
public record DesktopSetupStatusResponse(
        boolean setupRequired,
        String businessId,
        String shopName,
        String shopHost,
        String cloudOrigin
) {
}
