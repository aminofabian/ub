package zelisline.ub.payroll.api.dto;

public record StaffSmsSendResponse(
        boolean sent,
        String phone,
        String renderedBody,
        String providerStatus,
        String staffName
) {
}
