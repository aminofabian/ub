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
    NOTIFICATION("notification"),
    /** SMS Hub campaign to a customer audience (promotional). */
    CAMPAIGN("campaign"),
    /** SMS Hub automatic rule: thank-you, win-back. */
    AUTOMATION("automation"),
    /** AI brand kit (logos + favicon + share image) after the free allowance. */
    AI_LOGO("ai_logo");

    private final String code;

    SmsSendReason(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
