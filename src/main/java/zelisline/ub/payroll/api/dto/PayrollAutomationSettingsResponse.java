package zelisline.ub.payroll.api.dto;

import java.util.List;

public record PayrollAutomationSettingsResponse(
        boolean enabled,
        String automationMode,
        int payDayOfMonth,
        List<String> autoPayTimes,
        boolean applyStatutory,
        boolean postExpense,
        String paymentMethod,
        String branchId,
        String autoPayLastRunSlot
) {
}
