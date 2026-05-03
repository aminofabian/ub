package zelisline.ub.sales;

import zelisline.ub.finance.LedgerAccountCodes;

public final class SalePaymentLedger {

    private SalePaymentLedger() {
    }

    public static String ledgerCodeForPaymentMethod(String method) {
        if (SalesConstants.PAYMENT_METHOD_MPESA_MANUAL.equals(method)) {
            return LedgerAccountCodes.MPESA_CLEARING;
        }
        if (SalesConstants.PAYMENT_METHOD_CUSTOMER_CREDIT.equals(method)) {
            return LedgerAccountCodes.ACCOUNTS_RECEIVABLE_CUSTOMERS;
        }
        if (SalesConstants.PAYMENT_METHOD_CUSTOMER_WALLET.equals(method)) {
            return LedgerAccountCodes.CUSTOMER_WALLET_LIABILITY;
        }
        if (SalesConstants.PAYMENT_METHOD_LOYALTY_REDEEM.equals(method)) {
            return LedgerAccountCodes.LOYALTY_REDEMPTION_LIABILITY;
        }
        return LedgerAccountCodes.OPERATING_CASH;
    }
}
