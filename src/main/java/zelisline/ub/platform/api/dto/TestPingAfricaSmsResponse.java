package zelisline.ub.platform.api.dto;

/** Outcome of a Ping Africa test SMS from platform integrations. */
public record TestPingAfricaSmsResponse(
        String channel,
        String outcome,
        String detail,
        String phoneMasked,
        Double balanceAfter
) {
}
