package zelisline.ub.identity.api.dto;

/** Outcome of a super-admin test SMS to verify platform SMS delivery. */
public record SuperAdminTestSmsResponse(
        String channel,
        String outcome,
        String detail,
        String phoneMasked
) {
}
