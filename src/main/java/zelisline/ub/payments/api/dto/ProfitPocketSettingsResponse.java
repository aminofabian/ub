package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;

public record ProfitPocketSettingsResponse(
        boolean enabled,
        boolean configured,
        String destinationType,
        String destinationLabel,
        String destinationAccount,
        String destinationBankName,
        String destinationPaybill,
        String destinationPaybillAccount,
        BigDecimal defaultFloat,
        String destinationSummary,
        String marginGuardMode,
        boolean fridayReminderEnabled,
        /** True when till/paybill matches an active customer-pay endpoint. */
        boolean collidesWithCustomerPay,
        String customerPayCollisionMessage
) {
}
