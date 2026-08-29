package zelisline.ub.messaging.domain;

/**
 * Classification of a metered SMS send, stored on each {@link SmsCreditLedgerEntry}
 * {@code reason} column. {@link #GENERAL} is the catch-all for dispatch paths that
 * have not been classified yet (see SMS_CREDITS_SCOPE.md §6).
 */
public enum SmsSendReason {
    GENERAL("sms"),
    OTP("otp"),
    CREDIT_REMINDER("credit_reminder"),
    OPS_ALERT("ops_alert"),
    PAYROLL("payroll"),
    NOTIFICATION("notification");

    private final String code;

    SmsSendReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
