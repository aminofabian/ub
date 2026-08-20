package zelisline.ub.desktop.api.dto;

/**
 * Result of connecting the desktop install to an existing online shop.
 * After a successful connect the frontend routes to the staff login, exactly
 * like the create-shop wizard does.
 */
public record DesktopConnectResponse(
        String businessId,
        String branchId,
        String message
) {}
