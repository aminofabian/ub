package zelisline.ub.tenancy.api.dto;

import java.time.Instant;

/**
 * Store build / submit progress for a tenant's white-label shopper app.
 */
public record MobilePublishStatusResponse(
        String status,
        Instant requestedAt,
        String app,
        String platform,
        String workflowUrl,
        String lastError,
        Instant completedAt,
        boolean automationConfigured,
        String manualCommand
) {
    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_REQUESTED = "requested";
    public static final String STATUS_BUILDING = "building";
    public static final String STATUS_SUBMITTED = "submitted";
    public static final String STATUS_FAILED = "failed";
}
