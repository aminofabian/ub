package zelisline.ub.payroll.api.dto;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record PayrollAutomationSettingsRequest(
        Boolean enabled,
        @Size(max = 32) String automationMode,
        @Min(1) @Max(28) Integer payDayOfMonth,
        List<@Size(max = 5) String> autoPayTimes,
        Boolean applyStatutory,
        Boolean postExpense,
        @Size(max = 32) String paymentMethod,
        @Size(max = 36) String branchId
) {
}
