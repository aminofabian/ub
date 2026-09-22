package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
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
        Boolean fridayReminderEnabled,
        @DecimalMin("1.00") @DecimalMax("100.00") BigDecimal profitJarPct,
        @DecimalMin("0.00") BigDecimal marginBudgetDaily,
        /** daraja | kopokopo */
        @Size(max = 16) String sendRail
) {
}
