package zelisline.ub.opsalerts.api.dto;

public record OpsAlertTestSendResponse(
        String channel,
        String outcome,
        String detail,
        String phoneMasked
) {
}
