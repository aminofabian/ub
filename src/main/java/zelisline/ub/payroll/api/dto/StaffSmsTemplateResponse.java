package zelisline.ub.payroll.api.dto;

import java.util.List;

public record StaffSmsTemplateResponse(
        String key,
        String label,
        String description,
        String defaultBody,
        List<String> placeholders
) {
}
