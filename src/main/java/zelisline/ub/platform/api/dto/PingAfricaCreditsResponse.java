package zelisline.ub.platform.api.dto;

/**
 * Live Ping Africa organization credit balance from {@code GET /credits/balance}.
 */
public record PingAfricaCreditsResponse(
        boolean ok,
        Double balance,
        String detail,
        int httpStatus,
        boolean configured
) {
}
