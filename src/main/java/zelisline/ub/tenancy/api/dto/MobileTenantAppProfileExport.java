package zelisline.ub.tenancy.api.dto;

/**
 * One app entry inside {@link MobileTenantProfileExport}.
 */
public record MobileTenantAppProfileExport(
        String name,
        String expoSlug,
        String bundleId
) {}
