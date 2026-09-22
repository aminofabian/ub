package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

public record ProfitPocketSettingsRequest(
        Boolean enabled,
        @Size(max = 32) String destinationType,
        @Size(max = 120) String destinationLabel,
        @Size(max = 64) String destinationAccount,
        @Size(max = 120) String destinationBankName,
        @Size(max = 32) String destinationPaybill,
        @Size(max = 64) String destinationPaybillAccount,
        @DecimalMin("0.00") BigDecimal defaultFloat,
        @Size(max = 16) String marginGuardMode,
        Boolean fridayReminderEnabled
) {
}
