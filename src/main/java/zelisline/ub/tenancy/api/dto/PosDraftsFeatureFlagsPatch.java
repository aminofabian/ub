package zelisline.ub.tenancy.api.dto;

/**
 * Partial update for cashier POS draft feature flags under
 * {@code settings.featureFlags}.
 */
public record PosDraftsFeatureFlagsPatch(
        Boolean enabled,
        Boolean uiVisible,
        Boolean shadowWrites,
        Boolean offlineMirror
) {
}
