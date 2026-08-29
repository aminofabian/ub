package zelisline.ub.payroll.api.dto;

public record StaffSmsPreviewResponse(
        String templateKey,
        String renderedBody,
        String phone,
        boolean phoneAvailable,
        String staffName
) {
}
