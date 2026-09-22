package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;
import java.util.List;

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
        String customerPayCollisionMessage,
        /** 1–100 share of surplus; null means 100%. */
        BigDecimal profitJarPct,
        /** Daily below-cost loss budget (KES); null/0 = off. */
        BigDecimal marginBudgetDaily,
        /** daraja | kopokopo | null */
        String sendRail,
        List<ProfitPocketSendRailOption> availableSendRails,
        /** M-Pesa phone for Daraja Express STK (PartyA). */
        String stkPhone
) {
}
